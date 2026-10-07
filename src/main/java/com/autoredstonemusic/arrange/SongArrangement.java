package com.autoredstonemusic.arrange;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 编排产物：MIDI → 音符盒世界的全部决策结果。放置/清拆/重建的唯一事实源。
 *
 * <p>只序列化"音符层"（songName/totalTicks/lines/notes）；红石链由 {@link ChainCompiler}
 * 在放置端按当前引擎重新编译并自检——同一份编排可在任何引擎下复现。
 */
public final class SongArrangement {
    /** 触发类型：左侧枝路 / 右侧枝路 / 线上方（仅竖琴类可用）。 */
    public static final int TRIGGER_LEFT = 0;
    public static final int TRIGGER_RIGHT = 1;
    public static final int TRIGGER_ABOVE = 2;

    public record ArrangedNote(int tick, int pitch, int velocity, int trigger, String instrument, int midiKey, String family, int gainIdx) {}

    public static final class ArrangedLine {
        public final boolean drum;
        public final String instrument; // 声部主乐器（鼓组线为首个音符的乐器）
        public final List<ArrangedNote> notes = new ArrayList<>(); // 按 slot 升序
        /** 响度（音符速度总和），决定由近到远的位置分配；瞬态，不参与序列化（线序已固化）。 */
        public transient long loudness;

        public ArrangedLine(boolean drum, String instrument) {
            this.drum = drum;
            this.instrument = instrument;
        }
    }

    public String songName = "";
    public int totalTicks;
    public final List<ArrangedLine> lines = new ArrayList<>();
    /** 编排统计（UI 报告用，不参与世界重建）。 */
    public int totalNotes;
    public int foldedNotes;
    public int quantizedSlots;

    // ---- 序列化 ----

    public byte[] toBytes() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 16);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeInt(0x41524D35); // 'ARM5'（v5：音符携带响度增益）
            byte[] name = songName.getBytes(StandardCharsets.UTF_8);
            out.writeShort(name.length);
            out.write(name);
            out.writeInt(totalTicks);
            out.writeInt(lines.size());
            for (ArrangedLine line : lines) {
                out.writeBoolean(line.drum);
                byte[] instrument = line.instrument.getBytes(StandardCharsets.US_ASCII);
                out.writeByte(instrument.length);
                out.write(instrument);
                out.writeInt(line.notes.size());
                int prevTick = 0;
                for (ArrangedNote note : line.notes) {
                    writeVarInt(out, note.tick - prevTick);
                    prevTick = note.tick;
                    out.writeByte(note.pitch);
                    out.writeByte(note.velocity);
                    out.writeByte(note.trigger);
                    out.writeByte(note.midiKey);
                    byte[] noteInstrument = note.instrument.getBytes(StandardCharsets.US_ASCII);
                    out.writeByte(noteInstrument.length);
                    out.write(noteInstrument);
                    byte[] noteFamily = note.family.getBytes(StandardCharsets.US_ASCII);
                    out.writeByte(noteFamily.length);
                    out.write(noteFamily);
                    out.writeByte(note.gainIdx);
                }
            }
            out.writeInt(totalNotes);
            out.writeInt(foldedNotes);
            out.writeInt(quantizedSlots);
            out.close();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("编排序列化失败", e);
        }
    }

    public static SongArrangement fromBytes(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(bytes));
        SongArrangement a = new SongArrangement();
        if (in.readInt() != 0x41524D35) {
            throw new IOException("编排数据版本不匹配");
        }
        byte[] name = new byte[in.readUnsignedShort()];
        in.readFully(name);
        a.songName = new String(name, StandardCharsets.UTF_8);
        a.totalTicks = in.readInt();
        int lineCount = in.readInt();
        for (int i = 0; i < lineCount; i++) {
            boolean drum = in.readBoolean();
            byte[] instrument = new byte[in.readUnsignedByte()];
            in.readFully(instrument);
            ArrangedLine line = new ArrangedLine(drum, new String(instrument, StandardCharsets.US_ASCII));
            int noteCount = in.readInt();
            int tick = 0;
            for (int j = 0; j < noteCount; j++) {
                tick += readVarInt(in);
                int pitch = in.readUnsignedByte();
                int velocity = in.readUnsignedByte();
                int trigger = in.readUnsignedByte();
                int midiKey = in.readUnsignedByte();
                byte[] noteInstrument = new byte[in.readUnsignedByte()];
                in.readFully(noteInstrument);
                byte[] noteFamily = new byte[in.readUnsignedByte()];
                in.readFully(noteFamily);
                int gainIdx = in.readUnsignedByte();
                if (pitch < 0 || pitch > 24 || tick < 0 || tick >= a.totalTicks) {
                    throw new IOException("编排数据越界");
                }
                line.notes.add(new ArrangedNote(tick, pitch, velocity, trigger,
                        new String(noteInstrument, StandardCharsets.US_ASCII), midiKey,
                        new String(noteFamily, StandardCharsets.US_ASCII), gainIdx));
            }
            a.lines.add(line);
        }
        a.totalNotes = in.readInt();
        a.foldedNotes = in.readInt();
        a.quantizedSlots = in.readInt();
        return a;
    }

    static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
            if (shift > 28) {
                throw new IOException("varint 溢出");
            }
        }
    }
}
