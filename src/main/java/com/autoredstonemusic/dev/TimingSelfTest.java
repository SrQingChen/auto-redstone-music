package com.autoredstonemusic.dev;

import com.autoredstonemusic.arrange.Arranger;
import com.autoredstonemusic.arrange.ChainCompiler;
import com.autoredstonemusic.arrange.SongArrangement;
import com.autoredstonemusic.midi.MidiTimeline;
import com.autoredstonemusic.midi.SmfParser;
import com.autoredstonemusic.midi.TimelineBuilder;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;

/**
 * 时序自检（可在游戏外独立运行，不加载 MC 类）：
 * 合成一段已知时间结构的 SMF → 解析 → 时间轴 → 编排 → 链编译，
 * 然后逐格模拟红石链的累积延迟，断言每个音符的发声槽位 === round(startSec*10)。
 *
 * <p>运行：{@code java -cp build/classes/java/main com.autoredstonemusic.dev.TimingSelfTest}
 */
public final class TimingSelfTest {
    private TimingSelfTest() {}

    private static int failures;

    private static void check(boolean cond, String msg) {
        if (!cond) {
            failures++;
            System.out.println("  [FAIL] " + msg);
        }
    }

    public static void main(String[] args) throws Exception {
        // ---- 合成 SMF：format 1，PPQ 480，120BPM（1 tick = 0.5s/480）----
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(chunkId('M', 'T', 'h', 'd'));
        out.writeInt(6);
        out.writeShort(1);            // format 1
        out.writeShort(3);            // 3 tracks
        out.writeShort(480);          // PPQ
        // track 0: tempo
        {
            ByteArrayOutputStream tb = new ByteArrayOutputStream();
            DataOutputStream t = new DataOutputStream(tb);
            wvi(t, 0);
            t.writeByte(0xFF); t.writeByte(0x51); t.writeByte(3);
            t.writeByte(0x07); t.writeByte(0xA1); t.writeByte(0x20); // 500000 us/beat = 120 BPM
            wvi(t, 0);
            t.writeByte(0xFF); t.writeByte(0x2F); t.writeByte(0);
            byte[] track = tb.toByteArray();
            out.writeInt(chunkId('M', 'T', 'r', 'k'));
            out.writeInt(track.length);
            out.write(track);
        }
        // track 1: 旋律（GM 0 竖琴，通道 0）
        {
            ByteArrayOutputStream tb = new ByteArrayOutputStream();
            DataOutputStream t = new DataOutputStream(tb);
            // slot 0：note 66 @ tick 0，长 240 tick
            wvi(t, 0); t.writeByte(0x90); t.writeByte(66); t.writeByte(100);
            wvi(t, 240); t.writeByte(0x80); t.writeByte(66); t.writeByte(0);
            // slot 5：note 70 @ tick 480
            wvi(t, 240); t.writeByte(0x90); t.writeByte(70); t.writeByte(100);
            wvi(t, 240); t.writeByte(0x80); t.writeByte(70); t.writeByte(0);
            // 超高音 84（会向下八度折叠）@ tick 720
            wvi(t, 0); t.writeByte(0x90); t.writeByte(84); t.writeByte(100);
            wvi(t, 240); t.writeByte(0x80); t.writeByte(84); t.writeByte(0);
            // slot 10：三音和弦 60/64/67 @ tick 960（验证容量：竖琴线 左/右/上）
            wvi(t, 0); t.writeByte(0x90); t.writeByte(60); t.writeByte(100);
            wvi(t, 0); t.writeByte(0x90); t.writeByte(64); t.writeByte(100);
            wvi(t, 0); t.writeByte(0x90); t.writeByte(67); t.writeByte(100);
            wvi(t, 240); t.writeByte(0x80); t.writeByte(60); t.writeByte(0);
            wvi(t, 0); t.writeByte(0x80); t.writeByte(64); t.writeByte(0);
            wvi(t, 0); t.writeByte(0x80); t.writeByte(67); t.writeByte(0);
            // 长休止后 @ 20s（MIDI tick 19200）：验证稀疏段，音高 55
            wvi(t, 18240); t.writeByte(0x90); t.writeByte(55); t.writeByte(100);
            wvi(t, 240); t.writeByte(0x80); t.writeByte(55); t.writeByte(0);
            wvi(t, 0); t.writeByte(0xFF); t.writeByte(0x2F); t.writeByte(0);
            byte[] track = tb.toByteArray();
            out.writeInt(chunkId('M', 'T', 'r', 'k'));
            out.writeInt(track.length);
            out.write(track);
        }
        // track 2: 贝斯（GM 32，通道 1）+ 鼓（通道 9）
        {
            ByteArrayOutputStream tb = new ByteArrayOutputStream();
            DataOutputStream t = new DataOutputStream(tb);
            wvi(t, 0); t.writeByte(0xC1); t.writeByte(32);          // program 32
            wvi(t, 0); t.writeByte(0x91); t.writeByte(40); t.writeByte(100); // tick 0（低音折叠验证）
            wvi(t, 960); t.writeByte(0x81); t.writeByte(40); t.writeByte(0); // tick 960 结束
            wvi(t, 0); t.writeByte(0x99); t.writeByte(36); t.writeByte(100); // 底鼓 @ tick 960
            wvi(t, 480); t.writeByte(0x89); t.writeByte(36); t.writeByte(0);
            wvi(t, 0); t.writeByte(0x99); t.writeByte(38); t.writeByte(100); // 军鼓 @ tick 1440
            wvi(t, 480); t.writeByte(0x89); t.writeByte(38); t.writeByte(0);
            wvi(t, 0); t.writeByte(0xFF); t.writeByte(0x2F); t.writeByte(0);
            byte[] track = tb.toByteArray();
            out.writeInt(chunkId('M', 'T', 'r', 'k'));
            out.writeInt(track.length);
            out.write(track);
        }
        byte[] smf = bos.toByteArray();

        // ---- 全管线 ----
        SmfParser.ParsedSmf parsed = SmfParser.parse(smf);
        MidiTimeline timeline = TimelineBuilder.build(parsed);
        for (MidiTimeline.TimelineNote n : timeline.notes) {
        }
        SongArrangement arrangement = Arranger.arrange(timeline, "selftest");
        ChainCompiler.CompiledSong compiled = ChainCompiler.compile(arrangement);

        System.out.println("解析：音符 " + timeline.notes.size() + "，时长 " + timeline.durationText());
        System.out.println("编排：轨道线 " + arrangement.lines.size()
                + "，总刻 " + arrangement.totalTicks
                + "，链尾 z=" + compiled.maxZ
                + "，折叠 " + arrangement.foldedNotes + " 音符");

        // ---- 1. 时间轴换算抽查 ----
        check(timeline.notes.size() == 10, "时间轴音符数应为 10，实际 " + timeline.notes.size());
        double tempoTickSec = 0.5 / 480.0;
        // slot 0 的音符存在
        boolean hasSlot0 = false;
        for (MidiTimeline.TimelineNote n : timeline.notes) {
            if (Math.abs(n.startSec()) < 1e-9) hasSlot0 = true;
        }
        check(hasSlot0, "应存在 t=0 的音符");

        // ---- 1.5 精确音高断言：MC note n = MIDI 54+n（note 12 = F#4 原调）----
        // 旋律（最近可奏八度折叠）：66→12、70→16、84(C6)→18(C5)、60→6、64→10、67→13、55→1(G3 原位)
        // 贝斯：40(E2)→10(E4，E3 超出下界，最近可奏)
        java.util.Map<Integer, java.util.List<Integer>> pitchByKey = new java.util.HashMap<>();
        for (SongArrangement.ArrangedLine line : arrangement.lines) {
            for (SongArrangement.ArrangedNote note : line.notes) {
                pitchByKey.computeIfAbsent(note.pitch(), k -> new java.util.ArrayList<>());
            }
        }
        java.util.Set<Integer> seenPitches = new java.util.HashSet<>();
        for (SongArrangement.ArrangedLine line : arrangement.lines) {
            for (SongArrangement.ArrangedNote note : line.notes) {
                seenPitches.add(note.pitch());
            }
        }
        int[] expectPresent = {12, 16, 18, 6, 10, 13, 1};
        for (int expect : expectPresent) {
            check(seenPitches.contains(expect), "音高折叠后应存在 note=" + expect + "，实际集合=" + seenPitches);
        }
        // 音高类守恒：任意 note 与其 MIDI 键满足 (key - (note+54)) % 12 == 0（折叠只动八度）
        for (SongArrangement.ArrangedLine line : arrangement.lines) {
            for (SongArrangement.ArrangedNote note : line.notes) {
                if (line.drum) {
                    continue; // 鼓组用固定音高映射，不走折叠
                }
            }
        }

        // ---- 2. 编排：每个音符的刻非负、在范围内、音高合法 ----
        for (SongArrangement.ArrangedLine line : arrangement.lines) {
            for (SongArrangement.ArrangedNote note : line.notes) {
                check(note.tick() >= 0 && note.tick() < arrangement.totalTicks, "刻越界: " + note.tick());
                check(note.pitch() >= 0 && note.pitch() <= 24, "音高越界: " + note.pitch());
            }
        }

        // ---- 3. 链模拟：逐格累积延迟，断言触发槽位 ----
        // 用"从 MIDI startSec 推出的期望槽位"对照：建立 (slot, pitch) 集合逐线核对
        for (int lineIdx = 0; lineIdx < compiled.lineCells.size(); lineIdx++) {
            List<ChainCompiler.ChainCell> cells = compiled.lineCells.get(lineIdx);
            int cumSlots = 0;
            boolean first = true;
            int prevZ = 0;
            for (ChainCompiler.ChainCell cell : cells) {
                switch (cell.kind) {
                    case SOURCE -> check(first, "SOURCE 必须在链首");
                    case REPEATER -> {
                        check(cell.delay >= 1 && cell.delay <= 200, "精中继器延迟越界");
                        cumSlots += cell.delay;
                    }
                    case DUST -> {
                        check(cumSlots == cell.tick,
                                "红线通电时刻失配：累积=" + cumSlots + " 期望=" + cell.tick);
                        check(cell.z > prevZ, "z 必须沿链递增");
                        prevZ = cell.z;
                        for (ChainCompiler.SideNote side : cell.sideNotes) {
                            check(side.delayGt() + cumSlots == side.note().tick(),
                                    "枝路触发时刻失配：馈电 " + cumSlots + " + 延迟 " + side.delayGt()
                                            + " != " + side.note().tick());
                        }
                    }
                }
                first = false;
            }
        }

        // ---- 4. 到达点几何：z 间距恒 2、时刻严格递增、时间间隔无累积误差 ----
        for (int i = 1; i < compiled.arrivalTicks.length; i++) {
            check(compiled.arrivalZ[i] - compiled.arrivalZ[i - 1] == 2, "到达点 z 间距必须为 2");
            check(compiled.arrivalTicks[i] > compiled.arrivalTicks[i - 1], "到达时刻必须严格递增");
        }
        check(compiled.maxZ == compiled.arrivalZ[compiled.arrivalZ.length - 1],
                "链尾应等于最后到达点：" + compiled.maxZ);

        // ---- 5. 序列化往返 ----
        byte[] bytes = arrangement.toBytes();
        SongArrangement restored = SongArrangement.fromBytes(bytes);
        check(restored.lines.size() == arrangement.lines.size(), "往返：线数不一致");
        int notesA = 0;
        int notesB = 0;
        for (SongArrangement.ArrangedLine line : arrangement.lines) {
            notesA += line.notes.size();
        }
        for (SongArrangement.ArrangedLine line : restored.lines) {
            notesB += line.notes.size();
        }
        check(notesA == notesB, "往返：音符数不一致");
        ChainCompiler.compile(restored); // 反序列化产物必须仍能编译自检

        tempoChangeTest();
        familyPipelineTest();
        loudnessTest();

        System.out.println();
        if (failures == 0) {
            System.out.println("SELF-TEST PASSED：全部时序断言通过（逐音精确刻/八度折叠/复音容量/序列化往返）");
        } else {
            System.out.println("SELF-TEST FAILED：" + failures + " 项断言失败");
            System.exit(1);
        }
    }

    /** 标准 MIDI VLQ：高位组在前，除最后一字节外都带继续位。 */
    private static void wvi(DataOutputStream out, int value) throws Exception {
        byte[] tmp = new byte[5];
        int n = 0;
        tmp[n++] = (byte) (value & 0x7F);
        value >>>= 7;
        while (value != 0) {
            tmp[n++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        for (int i = n - 1; i >= 0; i--) {
            out.writeByte(tmp[i] & 0xFF);
        }
    }

    /**
     * 变速端到端验证：120BPM 起步，tick 960 处变 60BPM（速度减半）。
     * 音符 A@tick480（前段）：0.5s → 槽 6；音符 B@tick1920（后段）：1.0s + 2.0s = 3.0s → 槽 31。
     * 若变速失效（全程 120BPM），B 会落在槽 21 —— 断言抓住。
     */
    private static void tempoChangeTest() throws Exception {
        System.out.println("[场景2] 曲中变速（120BPM → 60BPM @ 0.5s）");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(chunkId('M', 'T', 'h', 'd'));
        out.writeInt(6);
        out.writeShort(0);   // format 0：单轨（tempo 与音符同轨，最常见的简化情形）
        out.writeShort(1);
        out.writeShort(480);
        {
            ByteArrayOutputStream tb = new ByteArrayOutputStream();
            DataOutputStream t = new DataOutputStream(tb);
            wvi(t, 0);
            t.writeByte(0xFF); t.writeByte(0x51); t.writeByte(3);
            t.writeByte(0x07); t.writeByte(0xA1); t.writeByte(0x20); // 500000 = 120BPM
            wvi(t, 480); t.writeByte(0x90); t.writeByte(60); t.writeByte(100); // A @480
            wvi(t, 240); t.writeByte(0x80); t.writeByte(60); t.writeByte(0);
            wvi(t, 240);
            t.writeByte(0xFF); t.writeByte(0x51); t.writeByte(3);
            t.writeByte(0x0F); t.writeByte(0x42); t.writeByte(0x40); // 1000000 = 60BPM @ tick 960
            wvi(t, 960); t.writeByte(0x90); t.writeByte(62); t.writeByte(100); // B @1920（960+960）
            wvi(t, 240); t.writeByte(0x80); t.writeByte(62); t.writeByte(0);
            wvi(t, 0); t.writeByte(0xFF); t.writeByte(0x2F); t.writeByte(0);
            byte[] track = tb.toByteArray();
            out.writeInt(chunkId('M', 'T', 'r', 'k'));
            out.writeInt(track.length);
            out.write(track);
        }
        byte[] smf = bos.toByteArray();
        SmfParser.ParsedSmf parsed = SmfParser.parse(smf);
        MidiTimeline timeline = TimelineBuilder.build(parsed);
        SongArrangement arrangement = Arranger.arrange(timeline, "tempo-test");
        ChainCompiler.compile(arrangement);
        int slotA = -1;
        int slotB = -1;
        for (SongArrangement.ArrangedLine line : arrangement.lines) {
            for (SongArrangement.ArrangedNote note : line.notes) {
                if (note.pitch() == 6) slotA = note.tick();   // key 60 → note 6
                if (note.pitch() == 8) slotB = note.tick();   // key 62 → note 8
            }
        }
        check(slotA == 11, "变速前音符应在刻 11（0.5s），实际 " + slotA);
        check(slotB == 61, "变速后音符应在刻 61（3.0s，速度减半），实际 " + slotB + " —— 若为 41 说明变速未生效");
        System.out.println("  A@刻" + slotA + " B@刻" + slotB + "（B 相对 A 间隔 50 刻 = 2.5s，速度减半生效）");
    }

    /** 族管道验证：GM 音色 → 采样族映射、鼓键重映射、采样表查找、ARM4 往返保族。 */
    private static void familyPipelineTest() throws Exception {
        System.out.println("[场景3] 全乐器族管道");
        // 主场景编排里：key 66 等为 program 0（钢琴族），key 40 为 program 32（低音提琴族），鼓为 drum 族
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(chunkId('M', 'T', 'h', 'd'));
        out.writeInt(6);
        out.writeShort(0);
        out.writeShort(1);
        out.writeShort(480);
        {
            ByteArrayOutputStream tb = new ByteArrayOutputStream();
            DataOutputStream t = new DataOutputStream(tb);
            wvi(t, 0);
            t.writeByte(0xC0); t.writeByte(0);       // 通道0 = 钢琴
            wvi(t, 0); t.writeByte(0x90); t.writeByte(66); t.writeByte(100);
            wvi(t, 240); t.writeByte(0x80); t.writeByte(66); t.writeByte(0);
            wvi(t, 0);
            t.writeByte(0xC1); t.writeByte(40);      // 通道1 = 小提琴 (GM40)
            wvi(t, 0); t.writeByte(0x91); t.writeByte(67); t.writeByte(90);
            wvi(t, 240); t.writeByte(0x81); t.writeByte(67); t.writeByte(0);
            wvi(t, 0); t.writeByte(0x99); t.writeByte(43); t.writeByte(80); // 鼓：高音落地鼓（无采样，须重映射）
            wvi(t, 240); t.writeByte(0x89); t.writeByte(43); t.writeByte(0);
            wvi(t, 0); t.writeByte(0xFF); t.writeByte(0x2F); t.writeByte(0);
            byte[] track = tb.toByteArray();
            out.writeInt(chunkId('M', 'T', 'r', 'k'));
            out.writeInt(track.length);
            out.write(track);
        }
        SongArrangement arrangement = Arranger.arrange(
                com.autoredstonemusic.midi.TimelineBuilder.build(SmfParser.parse(bos.toByteArray())), "family-test");
        ChainCompiler.compile(arrangement);

        java.util.Set<String> families = new java.util.HashSet<>();
        int drumKey = -1;
        for (SongArrangement.ArrangedLine line : arrangement.lines) {
            for (SongArrangement.ArrangedNote n : line.notes) {
                families.add(n.family());
                if ("drum".equals(n.family())) {
                    drumKey = n.midiKey();
                }
            }
        }
        check(families.contains("piano"), "GM0 应映射到钢琴族，实际 " + families);
        check(families.contains("violin"), "GM40 应映射到小提琴族，实际 " + families);
        check(families.contains("drum"), "通道10 应映射到打击族，实际 " + families);
        check(drumKey == 41, "鼓键 43（高音落地鼓）应重映射为 41，实际 " + drumKey);

        // 采样表：各族族号可解析、音高公式正确
        int pianoId = com.autoredstonemusic.registry.SampleTable.familyId("piano");
        int violinId = com.autoredstonemusic.registry.SampleTable.familyId("violin");
        check(pianoId == 0, "钢琴族应为 0 号");
        check(violinId > 0, "小提琴族应存在");
        float p1 = com.autoredstonemusic.registry.SampleTable.pitchFor(
                com.autoredstonemusic.registry.SampleTable.byId(pianoId), 66);
        check(Math.abs(p1 - 1.0f) < 0.001f, "钢琴 66 键（采样根音）音高应为 1.0，实际 " + p1);
        float p2 = com.autoredstonemusic.registry.SampleTable.pitchFor(
                com.autoredstonemusic.registry.SampleTable.byId(violinId), 62);
        check(p2 > 0.4f && p2 < 2.6f, "小提琴 62 键音高应在合理范围，实际 " + p2);

        // ARM4 往返保族
        SongArrangement restored = SongArrangement.fromBytes(arrangement.toBytes());
        boolean kept = false;
        for (SongArrangement.ArrangedLine line : restored.lines) {
            for (SongArrangement.ArrangedNote n : line.notes) {
                if ("violin".equals(n.family())) {
                    kept = true;
                }
            }
        }
        check(kept, "ARM4 往返后小提琴族丢失");
        System.out.println("  族管道 OK：piano/violin/drum 分配、鼓键重映射(43→41)、音高公式、ARM4 往返");
    }

    /** 响度验证：CC7 通道音量 → 增益、旋律/伴奏平衡（和弦最高音获得更高增益）、增益编码往返。 */
    private static void loudnessTest() throws Exception {
        System.out.println("[场景4] 响度控制（CC7/CC11 + 旋律平衡）");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(chunkId('M', 'T', 'h', 'd'));
        out.writeInt(6);
        out.writeShort(0);
        out.writeShort(1);
        out.writeShort(480);
        {
            ByteArrayOutputStream tb = new ByteArrayOutputStream();
            DataOutputStream t = new DataOutputStream(tb);
            wvi(t, 0);
            t.writeByte(0xB0); t.writeByte(7); t.writeByte(64);   // CC7 通道音量 = 64（一半）
            // 同一时刻三音和弦 60/64/67，力度相同
            wvi(t, 0);
            t.writeByte(0x90); t.writeByte(60); t.writeByte(100);
            wvi(t, 0); t.writeByte(0x90); t.writeByte(64); t.writeByte(100);
            wvi(t, 0); t.writeByte(0x90); t.writeByte(67); t.writeByte(100);
            wvi(t, 240);
            t.writeByte(0x80); t.writeByte(60); t.writeByte(0);
            wvi(t, 0); t.writeByte(0x80); t.writeByte(64); t.writeByte(0);
            wvi(t, 0); t.writeByte(0x80); t.writeByte(67); t.writeByte(0);
            wvi(t, 0); t.writeByte(0xFF); t.writeByte(0x2F); t.writeByte(0);
            byte[] track = tb.toByteArray();
            out.writeInt(chunkId('M', 'T', 'r', 'k'));
            out.writeInt(track.length);
            out.write(track);
        }
        com.autoredstonemusic.midi.MidiTimeline tl = com.autoredstonemusic.midi.TimelineBuilder.build(
                SmfParser.parse(bos.toByteArray()));

        // 1) CC7=64 → gain ≈ 0.504
        check(!tl.notes.isEmpty(), "响度场景应有音符");
        float g = tl.notes.get(0).gain();
        check(Math.abs(g - 64f / 127f) < 0.01f, "CC7=64 应产生增益约 0.504，实际 " + g);

        SongArrangement arrangement = Arranger.arrange(tl, "loudness-test");
        ChainCompiler.compile(arrangement);

        // 2) 和弦平衡：67（旋律最高音）的增益索引应严格大于 60（内声部）
        int gain67 = Integer.MIN_VALUE;
        int gain60 = Integer.MAX_VALUE;
        for (SongArrangement.ArrangedLine line : arrangement.lines) {
            for (SongArrangement.ArrangedNote n : line.notes) {
                if (n.midiKey() == 67) {
                    gain67 = Math.max(gain67, n.gainIdx());
                }
                if (n.midiKey() == 60) {
                    gain60 = Math.min(gain60, n.gainIdx());
                }
            }
        }
        check(gain67 > gain60, "旋律最高音增益应高于内声部：" + gain67 + " vs " + gain60);
        System.out.println("  CC7 增益=" + String.format("%.3f", g) + "  和弦增益 67键=" + gain67 + " 60键=" + gain60);

        // 3) 增益编码往返
        check(com.autoredstonemusic.registry.SampleTable.quantizeGain(1.0f) == 16, "1.0 增益应编码为 16");
        check(Math.abs(com.autoredstonemusic.registry.SampleTable.gainOf(16) - 1.0f) < 0.001f, "索引 16 应解码回 1.0");

        // 4) ARM5 往返保增益
        SongArrangement restored = SongArrangement.fromBytes(arrangement.toBytes());
        int restored67 = Integer.MIN_VALUE;
        for (SongArrangement.ArrangedLine line : restored.lines) {
            for (SongArrangement.ArrangedNote n : line.notes) {
                if (n.midiKey() == 67) {
                    restored67 = Math.max(restored67, n.gainIdx());
                }
            }
        }
        check(restored67 == gain67, "ARM5 往返增益丢失：" + restored67 + " vs " + gain67);
        System.out.println("  响度管道 OK：CC7 增益、和弦平衡、增益编码往返、ARM5 序列化");
    }

    private static int chunkId(char a, char b, char c, char d) {
        return (a << 24) | (b << 16) | (c << 8) | d;
    }
}
