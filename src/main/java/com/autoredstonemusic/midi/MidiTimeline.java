package com.autoredstonemusic.midi;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义化时间轴：把 SMF 的 tick 世界换算成绝对秒，并处理延音踏板等控制器语义。
 * 这是编配器（arrange 包）的唯一输入。
 */
public final class MidiTimeline {
    /** 绝对时间轴上的一个音符。programKey 含 bank 折叠（program + 128*bankMSB）。 */
    /** gain = 通道音量（CC7）× 表情（CC11），0-1；默认 1.0。 */
    public record TimelineNote(
            double startSec, double endSec,
            int key, int velocity,
            int channel, int programKey, boolean drum,
            float gain) {}

    public record TempoChange(long tick, int usPerQuarter, double startSec) {}

    public record TimeSignature(long tick, int numerator, int denominator) {}

    public record TrackSummary(String name, int noteCount, int minKey, int maxKey,
                               List<Integer> channels, List<Integer> programs) {}

    public final List<TimelineNote> notes = new ArrayList<>();
    public final List<TempoChange> tempoChanges = new ArrayList<>();
    public final List<TimeSignature> timeSignatures = new ArrayList<>();
    public final List<TrackSummary> trackSummaries = new ArrayList<>();
    /** 兼容性/解析警告（UI 展示用）。 */
    public final List<String> warnings = new ArrayList<>();

    public int format;
    public boolean smpte;
    public int ppq;
    public double durationSec;
    public int bendEventCount;
    public int aftertouchEventCount;
    public int sustainPedalSections;
    /** SMPTE 模式下每 tick 秒数（仅 smpte==true 时有效）。 */
    public double usPerTickOverride;

    // ---- 事件统计（解析报告展示，用于核对翻译完整性）----
    public int noteOnCount;
    public int noteOffCount;
    /** 被延音踏板挂起、推迟到踏板抬起才结束的音符数。 */
    public int sustainParks;
    /** 同键重叠被新音截断的音符数。 */
    public int overlapTruncations;
    public int programChangeCount;
    public int controlChangeCount;
    public int tempoChangeCount;
    /** 音符持续时长统计（拍/秒）： 最短/最长/平均（含延音）。 */
    public double minNoteSec = -1;
    public double maxNoteSec = -1;
    public double avgNoteSec = -1;
    /** 被量化/规范化丢弃的异常事件计数。 */
    public int droppedEvents;

    public boolean isDrumChannel(int channel) {
        return channel == 9; // GM 打击乐通道（0 基）
    }

    public String durationText() {
        int total = (int) Math.round(durationSec);
        return "%d:%02d".formatted(total / 60, total % 60);
    }
}
