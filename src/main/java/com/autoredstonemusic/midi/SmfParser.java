package com.autoredstonemusic.midi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 标准 MIDI 文件（SMF）解析器。自研而不依赖 javax.sound.midi：
 * 不引入 java.desktop 模块依赖、对损坏文件可控降级、事件模型完全贴合后续编配需求。
 *
 * <p>支持：Type 0/1/2、变长数（VLQ）、running status、SysEx（F0/F7）、全部 Meta 事件、
 * SMPTE 时基（division 高位为 1 时）。
 */
public final class SmfParser {
    private SmfParser() {}

    // ---- 事件类型常量（MidiEvent.kind）----
    public static final int NOTE_ON = 1;
    public static final int NOTE_OFF = 2;
    public static final int CONTROL_CHANGE = 3;
    public static final int PROGRAM_CHANGE = 4;
    public static final int PITCH_BEND = 5;
    public static final int META = 6;
    public static final int SYSEX = 7;
    // 其余通道消息（压力/复音触后）只计数，不产生事件。

    /** Meta 事件类型。 */
    public static final int META_TEMPO = 0x51;
    public static final int META_TIME_SIG = 0x58;
    public static final int META_KEY_SIG = 0x59;
    public static final int META_TRACK_NAME = 0x03;
    public static final int META_PORT = 0x21;
    public static final int META_END_OF_TRACK = 0x2F;

    /** 解析产物：一条轨道的事件按 tick 升序。 */
    public static final class ParsedTrack {
        public final List<MidiEvent> events = new ArrayList<>();
        public String name = "";
        public int port;
        public long tickLength;
    }

    public static final class ParsedSmf {
        public final int format;
        /** division：若 (division & 0x8000) == 0 则为 PPQ；否则按 SMPTE 编码（高 8 位 = -fps，低 8 位 = 每帧 tick）。 */
        public final int division;
        public final List<ParsedTrack> tracks = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();

        ParsedSmf(int format, int division) {
            this.format = format;
            this.division = division;
        }

        public boolean isSmpte() {
            return (division & 0x8000) != 0;
        }

        /** SMPTE 帧率（仅 isSmpte() 时有效）。 */
        public int smpteFps() {
            return 256 - ((division >> 8) & 0xFF);
        }

        /** SMPTE 每帧 tick 数。 */
        public int smpteTicksPerFrame() {
            return division & 0xFF;
        }

        public int ppq() {
            return isSmpte() ? 480 : division;
        }
    }

    /** 单个 MIDI 事件。channel/port 仅对通道消息有效；data 仅对 META/SYSEX 有效。 */
    public static final class MidiEvent {
        public final int kind;
        public final long tick;
        public final int channel;
        public final int port;
        public final int a;    // NOTE: 音高 / CC: 控制器号 / PROGRAM: 音色 / META: meta 类型
        public final int b;    // NOTE/CC/PITCH_BEND: 值
        public final byte[] data;

        MidiEvent(int kind, long tick, int channel, int port, int a, int b, byte[] data) {
            this.kind = kind;
            this.tick = tick;
            this.channel = channel;
            this.port = port;
            this.a = a;
            this.b = b;
            this.data = data;
        }
    }

    public static ParsedSmf parse(byte[] bytes) throws IOException {
        Reader r = new Reader(bytes);
        ParsedSmf out = readHeader(r);
        int tracksSeen = 0;
        while (r.remaining() >= 8 && tracksSeen < maxTracks(out)) {
            int pos = r.position;
            int id = r.readInt();
            long len = r.readUnsignedInt();
            if (id == chunkId('M', 'T', 'h', 'd')) { // 尾部附加的重复头，跳过
                r.position = pos;
                r.skip(8 + len);
                continue;
            }
            if (id != chunkId('M', 'T', 'r', 'k')) {
                // 未知 chunk：跳过
                r.position = pos;
                r.skip(8 + len);
                continue;
            }
            long end = r.position + len;
            try {
                out.tracks.add(readTrack(r, end, out, tracksSeen));
            } catch (IOException e) {
                out.warnings.add("轨道 " + tracksSeen + " 解析中断：" + e.getMessage() + "，已跳过剩余事件");
                r.position = (int) Math.min(end, bytes.length);
            }
            r.position = (int) Math.max(r.position, Math.min(end, bytes.length));
            tracksSeen++;
        }
        if (out.tracks.isEmpty()) {
            throw new IOException("文件中没有有效的 MTrk 轨道");
        }
        return out;
    }

    private static int maxTracks(ParsedSmf smf) {
        return smf.format == 0 ? 1 : Integer.MAX_VALUE;
    }

    private static int chunkId(char a, char b, char c, char d) {
        return (a << 24) | (b << 16) | (c << 8) | d;
    }

    private static ParsedSmf readHeader(Reader r) throws IOException {
        int id = r.readInt();
        if (id != chunkId('M', 'T', 'h', 'd')) {
            throw new IOException("不是标准 MIDI 文件（缺少 MThd 头）");
        }
        long len = r.readUnsignedInt();
        if (len < 6) {
            throw new IOException("MThd 长度异常");
        }
        int format = r.readUnsignedShort();
        int ntrks = r.readUnsignedShort();
        int division = r.readUnsignedShort();
        r.skip(len - 6);
        if (format < 0 || format > 2) {
            throw new IOException("不支持的 MIDI 格式 type=" + format);
        }
        ParsedSmf smf = new ParsedSmf(format, division);
        if (smf.isSmpte()) {
            int fps = smf.smpteFps();
            if (fps <= 0 || smf.smpteTicksPerFrame() == 0) {
                throw new IOException("SMPTE 时基异常");
            }
        } else if (division == 0) {
            throw new IOException("PPQ 时基为 0");
        }
        // ntrks 只作参考（有些文件头计数不准），以实际读到的轨道为准。
        return smf;
    }

    private static ParsedTrack readTrack(Reader r, long end, ParsedSmf smf, int index) throws IOException {
        ParsedTrack track = new ParsedTrack();
        long tick = 0;
        int runningStatus = 0;
        while (r.position < end) {
            long delta = r.readVarInt();
            if (delta < 0) {
                throw new IOException("变长数溢出");
            }
            tick += delta;
            int status = r.readUnsignedByte();
            if (status < 0x80) {
                if (runningStatus == 0) {
                    throw new IOException("running status 无效");
                }
                r.position--; // 回退一个字节，按 running status 重新处理
                status = runningStatus;
            } else {
                runningStatus = (status >= 0xF0) ? 0 : status;
            }

            if (status == 0xFF) {
                int type = r.readUnsignedByte();
                long len = r.readVarInt();
                byte[] data = r.readBytes((int) Math.min(len, Integer.MAX_VALUE - 1));
                if (type == META_TRACK_NAME && track.name.isEmpty()) {
                    track.name = new String(data, StandardCharsets.UTF_8).trim();
                } else if (type == META_PORT && data.length >= 1) {
                    track.port = data[0] & 0xFF;
                } else if (type == META_END_OF_TRACK) {
                    track.tickLength = Math.max(track.tickLength, tick);
                    break;
                }
                track.events.add(new MidiEvent(META, tick, 0, track.port, type, 0, data));
                track.tickLength = Math.max(track.tickLength, tick);
            } else if (status == 0xF0 || status == 0xF7) {
                long len = r.readVarInt();
                byte[] data = r.readBytes((int) Math.min(len, 1 << 20));
                track.events.add(new MidiEvent(SYSEX, tick, 0, track.port, status, 0, data));
            } else {
                int type = status & 0xF0;
                int channel = status & 0x0F;
                switch (type) {
                    case 0x80 -> { // Note Off
                        int key = r.readUnsignedByte();
                        int vel = r.readUnsignedByte();
                        track.events.add(new MidiEvent(NOTE_OFF, tick, channel, track.port, key, vel, null));
                    }
                    case 0x90 -> { // Note On（速度 0 视作 Note Off，在时间轴构建时归一化）
                        int key = r.readUnsignedByte();
                        int vel = r.readUnsignedByte();
                        track.events.add(new MidiEvent(NOTE_ON, tick, channel, track.port, key, vel, null));
                    }
                    case 0xA0 -> r.skip(2); // PolyAftertouch：忽略
                    case 0xB0 -> { // Control Change
                        int cc = r.readUnsignedByte();
                        int val = r.readUnsignedByte();
                        track.events.add(new MidiEvent(CONTROL_CHANGE, tick, channel, track.port, cc, val, null));
                    }
                    case 0xC0 -> { // Program Change
                        int prog = r.readUnsignedByte();
                        track.events.add(new MidiEvent(PROGRAM_CHANGE, tick, channel, track.port, prog, 0, null));
                    }
                    case 0xD0 -> r.skip(1); // ChannelAftertouch：忽略
                    case 0xE0 -> { // Pitch Bend
                        int lsb = r.readUnsignedByte();
                        int msb = r.readUnsignedByte();
                        track.events.add(new MidiEvent(PITCH_BEND, tick, channel, track.port, (msb << 7) | lsb, 0, null));
                    }
                    default -> throw new IOException("未知状态字节 0x" + Integer.toHexString(status));
                }
            }
        }
        return track;
    }

    private static final class Reader {
        final byte[] bytes;
        int position;

        Reader(byte[] bytes) {
            this.bytes = bytes;
        }

        int remaining() {
            return bytes.length - position;
        }

        void skip(long n) throws IOException {
            if (position + n > bytes.length) {
                position = bytes.length;
                throw new IOException("数据越界");
            }
            position += (int) n;
        }

        int readUnsignedByte() throws IOException {
            if (position >= bytes.length) {
                throw new IOException("数据越界");
            }
            return bytes[position++] & 0xFF;
        }

        int readUnsignedShort() throws IOException {
            return (readUnsignedByte() << 8) | readUnsignedByte();
        }

        int readInt() throws IOException {
            return (readUnsignedByte() << 24) | (readUnsignedByte() << 16) | (readUnsignedByte() << 8) | readUnsignedByte();
        }

        long readUnsignedInt() throws IOException {
            return readInt() & 0xFFFFFFFFL;
        }

        long readVarInt() throws IOException {
            long value = 0;
            for (int i = 0; i < 4; i++) {
                int b = readUnsignedByte();
                value = (value << 7) | (b & 0x7F);
                if ((b & 0x80) == 0) {
                    return value;
                }
            }
            return -1;
        }

        byte[] readBytes(int n) throws IOException {
            if (n < 0 || position + n > bytes.length) {
                throw new IOException("数据越界");
            }
            byte[] out = new byte[n];
            System.arraycopy(bytes, position, out, 0, n);
            position += n;
            return out;
        }
    }
}
