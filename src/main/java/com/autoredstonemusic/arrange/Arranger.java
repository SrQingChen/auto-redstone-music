package com.autoredstonemusic.arrange;

import com.autoredstonemusic.Config;
import com.autoredstonemusic.midi.MidiTimeline;
import com.autoredstonemusic.midi.TimelineBuilder;
import com.autoredstonemusic.midi.SmfParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排器：MIDI 时间轴 → 可放置的编排产物。
 *
 * <p>模块化流水线（每步独立、可组合）：
 * <ol>
 *   <li><b>声部分组</b>：按 (音色组, 是否鼓组) 聚合 —— 同通道中途换音色会自然拆组；</li>
 *   <li><b>时间量化</b>：绝对秒 → 0.1s 槽（四舍五入），超长截断并告警；</li>
 *   <li><b>音高折叠</b>：MIDI 0-127 → 音符盒 0-24，按乐器锚点八度折叠（跟踪方向，规避 NBS #516）；</li>
 *   <li><b>复音拆轨</b>：线内每槽容量 = 左右枝路各 1（红石线无法激活上方音符盒，无上方位），超出自动开新线；
 *       同刻和弦按音高降序分配，旋律最高音优先占位；</li>
 *   <li><b>线序与配额</b>：鼓组线优先，线数超上限时丢弃最稀疏的线并告警。</li>
 * </ol>
 * 产出 {@link SongArrangement}，随后由 {@link ChainCompiler} 编译成红石链。
 */
public final class Arranger {
    private Arranger() {}

    public static SongArrangement arrange(MidiTimeline tl, String songName) {
        SongArrangement out = new SongArrangement();
        out.songName = songName;

        // ---- 0. 旋律/伴奏判定预处理：每个音头刻的最高音（旋律候选）与音数 ----
        java.util.Map<Long, Integer> topKeyByTick = new java.util.HashMap<>();
        java.util.Map<Long, Integer> countByTick = new java.util.HashMap<>();
        for (MidiTimeline.TimelineNote note : tl.notes) {
            if (note.drum()) {
                continue;
            }
            long tk = Math.round(note.startSec() * Config.TICKS_PER_SECOND);
            topKeyByTick.merge(tk, note.key(), Math::max);
            countByTick.merge(tk, 1, Integer::sum);
        }

        // ---- 1. 声部分组 ----
        record GroupKey(boolean drum, int programKey) {}
        Map<GroupKey, List<MidiTimeline.TimelineNote>> groups = new LinkedHashMap<>();
        for (MidiTimeline.TimelineNote note : tl.notes) {
            groups.computeIfAbsent(new GroupKey(note.drum(), note.programKey()), k -> new ArrayList<>()).add(note);
        }

        // ---- 2/3. 量化 + 折叠，并做线内拆分 ----
        List<SongArrangement.ArrangedLine> allLines = new ArrayList<>();
        for (Map.Entry<GroupKey, List<MidiTimeline.TimelineNote>> entry : groups.entrySet()) {
            boolean drum = entry.getKey().drum();
            List<MidiTimeline.TimelineNote> notes = entry.getValue();
            // 同刻按音高降序：旋律（和弦最高音）优先占位，保证旋律清晰
            notes.sort(Comparator.comparingDouble(MidiTimeline.TimelineNote::startSec)
                    .thenComparing(Comparator.<MidiTimeline.TimelineNote>comparingInt(MidiTimeline.TimelineNote::key).reversed()));

            String groupInstrument = drum ? null : InstrumentMap.programInstrumentName(entry.getKey().programKey());
            String groupFamily = drum ? "drum" : InstrumentMap.programFamily(entry.getKey().programKey());
            List<DraftLine> drafts = new ArrayList<>();
            int rotate = 0;
            int dumped = 0;
            for (MidiTimeline.TimelineNote note : notes) {
                // 逐音精确刻：+1 前导刻保证枝路延迟 >=1；无栅格，无量化纹
                int slot = (int) Math.round(note.startSec() * Config.TICKS_PER_SECOND) + Config.LEADING_TICKS;
                if (slot < 0) {
                    slot = 0;
                }

                // 响度：CC7×CC11 通道增益 × 旋律/伴奏角色平衡（旋律最高音 +18%，内声部 -20%）
                long tickKey = Math.round(note.startSec() * Config.TICKS_PER_SECOND);
                float roleGain = 1.0f;
                if (!drum && countByTick.getOrDefault(tickKey, 1) > 1) {
                    Integer top = topKeyByTick.get(tickKey);
                    roleGain = (top != null && top == note.key()) ? 1.18f : 0.80f;
                }
                int gainIdx = com.autoredstonemusic.registry.SampleTable.quantizeGain(note.gain() * roleGain);

                int pitch;
                String noteInstrument;
                int soundKey = note.key();
                if (drum) {
                    InstrumentMap.DrumHit hit = InstrumentMap.forDrumKey(note.key());
                    pitch = hit.note();
                    noteInstrument = hit.instrument();
                    soundKey = InstrumentMap.drumSampleKey(note.key());
                } else {
                    pitch = fold(note.key());
                    if (pitch != note.key()) {
                        out.foldedNotes++;
                    }
                    noteInstrument = groupInstrument;
                    if ("drum".equals(groupFamily)) {
                        int ov = InstrumentMap.programDrumOverride(entry.getKey().programKey());
                        soundKey = InstrumentMap.drumSampleKey(ov > 0 ? ov : 60);
                    }
                }

                // 选触发位与线：先尝试已有线（轮转起点均衡负载），不行开新线
                int assigned = -1;
                int trigger = -1;
                for (int attempt = 0; attempt < drafts.size() && assigned < 0; attempt++) {
                    DraftLine draft = drafts.get((attempt + rotate) % drafts.size());
                    int t = draft.freeTrigger(slot, drum);
                    if (t >= 0) {
                        assigned = (attempt + rotate) % drafts.size();
                        trigger = t;
                    }
                }
                if (assigned < 0) {
                    drafts.add(new DraftLine(drum, noteInstrument));
                    assigned = drafts.size() - 1;
                    trigger = drafts.get(assigned).freeTrigger(slot, drum);
                    if (trigger < 0) {
                        // 理论不可达：新线必有空位
                        continue;
                    }
                }
                rotate = (assigned + 1) % Math.max(1, drafts.size());
                drafts.get(assigned).place(slot, pitch, note.velocity(), trigger, noteInstrument, soundKey, groupFamily, gainIdx);
                if (dumped < 500) {
                    dumped++;
                    com.autoredstonemusic.Log.debug("  音符对照: MIDI键 {} -> note {} @槽 {} 线{} {}",
                            note.key(), pitch, slot, assigned,
                            trigger == SongArrangement.TRIGGER_LEFT ? "左枝" : "右枝");
                }
            }
            for (DraftLine draft : drafts) {
                allLines.add(draft.toLine());
            }
        }

        // ---- 5. 线序与配额（用户定稿）----
        // 超过 13 条：保留"音最多"的 13 条；
        // 然后按响度（音符速度总和）降序排列 → 依次分配由近到远的位置（最响的贴着玩家）。
        if (allLines.size() > Config.MAX_LINES) {
            allLines.sort(Comparator.<SongArrangement.ArrangedLine>comparingInt(l -> -l.notes.size()));
            List<SongArrangement.ArrangedLine> kept = new ArrayList<>(allLines.subList(0, Config.MAX_LINES));
            tl.warnings.add("分轨数超上限（%d > %d），已按音符数量保留最密集的 %d 条"
                    .formatted(allLines.size(), Config.MAX_LINES, kept.size()));
            allLines = kept;
        }
        allLines.sort(Comparator.<SongArrangement.ArrangedLine>comparingLong(l -> -l.loudness)
                .thenComparing(Comparator.<SongArrangement.ArrangedLine>comparingInt(l -> -l.notes.size())));
        out.lines.addAll(allLines);
        if (com.autoredstonemusic.Log.verbose()) {
            for (int i = 0; i < allLines.size(); i++) {
                SongArrangement.ArrangedLine l = allLines.get(i);
                com.autoredstonemusic.Log.debug("线位 {}（{}）: 响度 {} 音符 {} 音色 {}",
                        i, i == 0 ? "最近" : i < 8 ? "两侧" : i < 16 ? "下一层" : "正下方",
                        l.loudness, l.notes.size(), l.instrument);
            }
        }

        // ---- 槽位上限 ----
        int maxSlot = -1;
        for (SongArrangement.ArrangedLine line : out.lines) {
            for (SongArrangement.ArrangedNote note : line.notes) {
                maxSlot = Math.max(maxSlot, note.tick());
            }
        }
        if (maxSlot < 0) {
            throw new IllegalArgumentException("没有可用的音符");
        }
        int rawSlots = maxSlot + 1 + Config.TAIL_TICKS;
        if (rawSlots > Config.MAX_TICKS) {
            tl.warnings.add("乐曲超出长度上限（%d 刻 > %d），已截断至 %.1f 秒".formatted(rawSlots, Config.MAX_TICKS, Config.MAX_TICKS / 20.0));
            rawSlots = Config.MAX_TICKS;
            for (SongArrangement.ArrangedLine line : out.lines) {
                line.notes.removeIf(n -> n.tick() >= Config.MAX_TICKS);
            }
        }
        out.totalTicks = rawSlots;

        int total = 0;
        for (SongArrangement.ArrangedLine line : out.lines) {
            total += line.notes.size();
        }
        out.totalNotes = total;
        if (out.lines.isEmpty() || total == 0) {
            throw new IllegalArgumentException("没有可用的音符");
        }
        return out;
    }

    /** 一条候选线：记录每槽的占用位（bit0=左 bit1=右 bit2=上）。 */
    private static final class DraftLine {
        final boolean drum;
        final String instrument;
        final Map<Integer, Integer> usage = new LinkedHashMap<>();
        final List<SongArrangement.ArrangedNote> notes = new ArrayList<>();
        long loudness;

        DraftLine(boolean drum, String instrument) {
            this.drum = drum;
            this.instrument = instrument;
        }

        /** 返回该槽的可用触发位（0=左 1=右），无空位返回 -1。
         *  注：v0.1.1 实测+反编译确认红石线无法激活其上方音符盒（hasNeighborSignal 对 DOWN 传 0），
         *  "线上方"触发位已废除，线容量 = 每槽左右各 1。 */
        int freeTrigger(int slot, boolean isDrumNote) {
            int used = usage.getOrDefault(slot, 0);
            if (slot >= 1 && (used & 1) == 0) {
                return SongArrangement.TRIGGER_LEFT;
            }
            if (slot >= 1 && (used & 2) == 0) {
                return SongArrangement.TRIGGER_RIGHT;
            }
            return -1;
        }

        void place(int slot, int pitch, int velocity, int trigger, String noteInstrument, int midiKey, String family, int gainIdx) {
            int bit = switch (trigger) {
                case SongArrangement.TRIGGER_LEFT -> 1;
                case SongArrangement.TRIGGER_RIGHT -> 2;
                default -> 4;
            };
            usage.merge(slot, bit, (oldBits, b) -> oldBits | b);
            loudness += Math.max(1, velocity);
            notes.add(new SongArrangement.ArrangedNote(slot, pitch, velocity, trigger, noteInstrument, midiKey, family, gainIdx));
        }

        SongArrangement.ArrangedLine toLine() {
            notes.sort(Comparator.comparingInt(SongArrangement.ArrangedNote::tick));
            SongArrangement.ArrangedLine line = new SongArrangement.ArrangedLine(drum, instrument);
            line.notes.addAll(this.notes);
            line.loudness = this.loudness;
            return line;
        }
    }

    /**
     * MIDI 键 → 音符盒 0-24。坐标系换算：音符盒 note n 发出 MIDI 54+n（note 0=F#3，note 12=F#4=音源原调，
     * 音高公式 2^((n-12)/12)），先做 key-54 平移，再以 ±12 的最小步进折入 0-24。
     * 这是"最近可奏八度"折叠：保持旋律原始八度轮廓（低音仍尽量低），且天然保持音高类与折叠方向
     * （规避 NBS issue #516）。此前的锚点折叠会把低音区强行抬八度，钢琴左右手被压进同一八度。
     */
    static int fold(int key) {
        int note = key - 54;
        while (note < 0) {
            note += 12;
        }
        while (note > 24) {
            note -= 12;
        }
        return note;
    }

    /** 便捷入口：解析 + 时间轴 + 编排一步完成（UI 后台线程调用）。 */
    public static SongArrangement arrangeFile(byte[] midiBytes, String songName) throws Exception {
        com.autoredstonemusic.Log.debug("编排开始: 输入 {}", com.autoredstonemusic.Log.bytesDiag(midiBytes));
        SmfParser.ParsedSmf smf = SmfParser.parse(midiBytes);
        MidiTimeline timeline = TimelineBuilder.build(smf);
        SongArrangement arrangement = arrange(timeline, songName);
        ChainCompiler.compile(arrangement); // 提前自检
        return arrangement;
    }
}
