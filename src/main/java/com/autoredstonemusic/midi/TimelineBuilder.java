package com.autoredstonemusic.midi;

import com.autoredstonemusic.midi.SmfParser.MidiEvent;
import com.autoredstonemusic.midi.SmfParser.ParsedSmf;
import com.autoredstonemusic.midi.SmfParser.ParsedTrack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SMF → 语义时间轴。
 *
 * <p>处理内容：
 * <ul>
 *   <li>tempo map（FF 51）与 SMPTE 时基 → tick 绝对秒换算（支持曲中变速）；</li>
 *   <li>Type 2：各轨道按顺序拼接；</li>
 *   <li>延音踏板 CC64：踏板踩下期间的 note-off 被推迟到踏板抬起；</li>
 *   <li>CC120/CC123（全部音关）：截断悬挂音；</li>
 *   <li>Note On 速度 0 → Note Off；同键重叠音符按"新音截断旧音"处理（音符盒物理上同一音块同一时刻只能发一个音）；</li>
 *   <li>bank select（CC0/CC32）折叠进 programKey，作为声部分组依据。</li>
 * </ul>
 */
public final class TimelineBuilder {
    private TimelineBuilder() {}

    public static MidiTimeline build(ParsedSmf smf) {
        MidiTimeline tl = new MidiTimeline();
        tl.warnings.addAll(smf.warnings); // 解析器级告警（轨道中断等）透传到 UI 报告
        tl.format = smf.format;
        tl.smpte = smf.isSmpte();
        tl.ppq = smf.ppq();

        // ---- tempo map ----
        List<long[]> tempoTicks = new ArrayList<>(); // {tick, usPerQuarter}
        for (ParsedTrack track : smf.tracks) {
            for (MidiEvent ev : track.events) {
                if (ev.kind == SmfParser.META && ev.a == SmfParser.META_TEMPO && ev.data != null && ev.data.length >= 3) {
                    int us = ((ev.data[0] & 0xFF) << 16) | ((ev.data[1] & 0xFF) << 8) | (ev.data[2] & 0xFF);
                    if (us > 0) {
                        tempoTicks.add(new long[]{ev.tick, us});
                    }
                } else if (ev.kind == SmfParser.META && ev.a == SmfParser.META_TIME_SIG && ev.data != null && ev.data.length >= 2) {
                    int den = 1 << (ev.data[1] & 0xFF);
                    if (den > 0) {
                        tl.timeSignatures.add(new MidiTimeline.TimeSignature(ev.tick, ev.data[0] & 0xFF, den));
                    }
                }
            }
        }
        tempoTicks.sort(Comparator.comparingLong(a -> a[0]));
        tl.tempoChangeCount = tempoTicks.size();
        if (tempoTicks.isEmpty() || tempoTicks.get(0)[0] > 0) {
            tempoTicks.add(0, new long[]{0, 500_000}); // 默认 120 BPM
        }
        if (tl.smpte) {
            double secPerTick = 1.0 / (smf.smpteFps() * (double) smf.smpteTicksPerFrame());
            tl.tempoChanges.add(new MidiTimeline.TempoChange(0, 0, 0));
            tl.usPerTickOverride = secPerTick;
        } else {
            double accSec = 0;
            long prevTick = 0;
            int prevUs = (int) tempoTicks.get(0)[1];
            for (long[] tc : tempoTicks) {
                accSec += (tc[0] - prevTick) * prevUs / (smf.ppq() * 1_000_000.0);
                tl.tempoChanges.add(new MidiTimeline.TempoChange(tc[0], (int) tc[1], accSec));
                prevTick = tc[0];
                prevUs = (int) tc[1];
            }
        }

        // ---- 事件展开 ----
        long type2Offset = 0;
        // 每轨道的 (channel+port) 状态
        for (int trackIdx = 0; trackIdx < smf.tracks.size(); trackIdx++) {
            ParsedTrack track = smf.tracks.get(trackIdx);
            ChannelState state = new ChannelState(track.port, tl);
            int trackNotesBefore = tl.notes.size();
            int minKey = 128;
            int maxKey = -1;
            Map<Integer, Integer> programsUsed = new HashMap<>();

            for (MidiEvent ev : track.events) {
                long tick = ev.tick + type2Offset;
                double sec = tickToSec(tl, tick);
                switch (ev.kind) {
                    case SmfParser.NOTE_ON -> {
                        tl.noteOnCount++;
                        if (ev.b > 0) {
                            state.noteOn(tick, sec, ev.channel, ev.a, ev.b);
                        } else {
                            state.noteOff(tick, sec, ev.channel, ev.a);
                        }
                    }
                    case SmfParser.NOTE_OFF -> {
                        tl.noteOffCount++;
                        state.noteOff(tick, sec, ev.channel, ev.a);
                    }
                    case SmfParser.PROGRAM_CHANGE -> {
                        tl.programChangeCount++;
                        state.programs.put(ev.channel, ev.a);
                        programsUsed.merge(ev.a, 1, Integer::sum);
                    }
                    case SmfParser.CONTROL_CHANGE -> {
                        tl.controlChangeCount++;
                        state.controlChange(tick, sec, ev.channel, ev.a, ev.b);
                    }
                    case SmfParser.PITCH_BEND -> tl.bendEventCount++;
                    default -> {
                        // META/SYSEX：已在上文统计
                    }
                }
            }
            state.flushAll(tickToSec(tl, track.tickLength + type2Offset), false);

            for (MidiTimeline.TimelineNote n : tl.notes) {
                if (n.key() < minKey) minKey = n.key();
                if (n.key() > maxKey) maxKey = n.key();
            }
            List<Integer> channels = new ArrayList<>(state.touchedChannels());
            channels.sort(Integer::compareTo);
            List<Integer> programs = new ArrayList<>(programsUsed.keySet());
            programs.sort(Integer::compareTo);
            int noteCount = tl.notes.size() - trackNotesBefore;
            String name = track.name.isBlank() ? "轨道 " + (trackIdx + 1) : track.name;
            tl.trackSummaries.add(new MidiTimeline.TrackSummary(name, noteCount, minKey < 128 ? minKey : 0,
                    maxKey < 0 ? 0 : maxKey, channels, programs));

            if (smf.format == 2) {
                type2Offset += track.tickLength;
            }
        }

        tl.notes.sort(Comparator.comparingDouble(MidiTimeline.TimelineNote::startSec));
        double maxEnd = 0;
        for (MidiTimeline.TimelineNote n : tl.notes) {
            maxEnd = Math.max(maxEnd, n.endSec());
        }
        tl.durationSec = maxEnd;
        if (!tl.notes.isEmpty()) {
            double sum = 0;
            for (MidiTimeline.TimelineNote n : tl.notes) {
                sum += n.endSec() - n.startSec();
            }
            tl.avgNoteSec = sum / tl.notes.size();
        }
        if (tl.smpte) {
            tl.warnings.add("SMPTE 时基（%.1f fps）".formatted((double) smf.smpteFps()));
        }
        if (tl.bendEventCount > 0) {
            tl.warnings.add("包含 %d 个弯音事件：音符盒无法表达弯音，已忽略".formatted(tl.bendEventCount));
        }
        return tl;
    }

    private static double tickToSec(MidiTimeline tl, long tick) {
        if (tl.smpte) {
            return tick * tl.usPerTickOverride;
        }
        // 二分找 <= tick 的最后一个 tempo 段
        int lo = 0;
        int hi = tl.tempoChanges.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (tl.tempoChanges.get(mid).tick() <= tick) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        MidiTimeline.TempoChange tc = tl.tempoChanges.get(lo);
        long delta = tick - tc.tick();
        return tc.startSec() + delta * tc.usPerQuarter() / (tl.ppq * 1_000_000.0);
    }

    /** 单条 SMF 轨道的通道状态（音色、延音踏板、悬挂音）。 */
    private static final class ChannelState {
        final MidiTimeline tl;
        final int port;
        /** channel -> PROGRAM_CHANGE 音色号（0-127）。 */
        final Map<Integer, Integer> programs = new HashMap<>();
        /** channel -> CC0 bank MSB（非 0 时折叠进 programKey 以区分音色组）。 */
        final Map<Integer, Integer> bankMsb = new HashMap<>();
        /** channel -> CC7 通道音量（默认 127）。 */
        final Map<Integer, Integer> cc7 = new HashMap<>();
        /** channel -> CC11 表情（默认 127）。 */
        final Map<Integer, Integer> cc11 = new HashMap<>();

        /** channel -> 是否踩下延音踏板。 */
        final Map<Integer, Boolean> pedal = new HashMap<>();
        /** channel -> 正在发声的音符（key -> note builder）。 */
        final Map<Integer, Map<Integer, OpenNote>> open = new HashMap<>();
        /** channel -> 被延音踏板挂起的音符。 */
        final Map<Integer, Map<Integer, OpenNote>> sustained = new HashMap<>();

        ChannelState(int port, MidiTimeline tl) {
            this.port = port;
            this.tl = tl;
        }

        int programKeyOf(int channel) {
            return programs.getOrDefault(channel, 0) + 128 * bankMsb.getOrDefault(channel, 0);
        }

        /** 通道增益 = CC7 × CC11（0-1，默认 1.0）。 */
        float channelGain(int channel) {
            return (cc7.getOrDefault(channel, 127) / 127.0f)
                    * (cc11.getOrDefault(channel, 127) / 127.0f);
        }

        void noteOn(long tick, double sec, int channel, int key, int velocity) {
            // 同键旧音先截断（无论 open 还是 sustained）
            Map<Integer, OpenNote> preOpen = open.get(channel);
            if ((preOpen != null && preOpen.containsKey(key))
                    || (sustained.get(channel) != null && sustained.get(channel).containsKey(key))) {
                tl.overlapTruncations++;
            }
            closeIfOpen(channel, key, sec, true);
            Map<Integer, OpenNote> chOpen = open.computeIfAbsent(channel, k -> new HashMap<>());
            chOpen.put(key, new OpenNote(channel, key, velocity, programKeyOf(channel), tick, sec, channelGain(channel)));
        }

        void noteOff(long tick, double sec, int channel, int key) {
            Map<Integer, OpenNote> chOpen = open.get(channel);
            if (chOpen != null && chOpen.containsKey(key)) {
                if (pedal.getOrDefault(channel, false)) {
                    // 踏板踩下：挂起，等抬起或被同键截断
                    Map<Integer, OpenNote> chSus = sustained.computeIfAbsent(channel, k -> new HashMap<>());
                    OpenNote removed = chOpen.remove(key);
                    if (removed != null) {
                        chSus.putIfAbsent(key, removed);
                        tl.sustainParks++;
                    }
                } else {
                    OpenNote n = chOpen.remove(key);
                    if (n != null) {
                        emit(n, sec);
                    }
                }
            }
        }

        void controlChange(long tick, double sec, int channel, int cc, int value) {
            switch (cc) {
                case 64 -> {
                    boolean down = value >= 64;
                    boolean was = pedal.getOrDefault(channel, false);
                    pedal.put(channel, down);
                    if (was && !down) {
                        // 抬起：结束全部挂起音
                        Map<Integer, OpenNote> chSus = sustained.remove(channel);
                        if (chSus != null) {
                            for (OpenNote n : chSus.values()) {
                                emit(n, sec);
                            }
                        }
                    } else if (down && !was) {
                        tl.sustainPedalSections++;
                    }
                }
                case 120, 123 -> flushChannel(channel, sec); // 全部音关 / 全部音止
                case 7 -> cc7.put(channel, value & 0x7F);   // 通道音量
                case 11 -> cc11.put(channel, value & 0x7F); // 表情
                case 0 -> bankMsb.put(channel, value & 0x7F);
                default -> {
                    // 其他控制器（CC32 bank LSB、表情、颤音等）不影响音符盒转换
                }
            }
        }

        void closeIfOpen(int channel, int key, double sec, boolean alsoSustained) {
            Map<Integer, OpenNote> chOpen = open.get(channel);
            if (chOpen != null) {
                OpenNote n = chOpen.remove(key);
                if (n != null) {
                    emit(n, sec);
                }
            }
            if (alsoSustained) {
                Map<Integer, OpenNote> chSus = sustained.get(channel);
                if (chSus != null) {
                    OpenNote n = chSus.remove(key);
                    if (n != null) {
                        emit(n, sec);
                    }
                }
            }
        }

        void flushChannel(int channel, double sec) {
            Map<Integer, OpenNote> chOpen = open.remove(channel);
            if (chOpen != null) {
                for (OpenNote n : chOpen.values()) {
                    emit(n, sec);
                }
            }
            Map<Integer, OpenNote> chSus = sustained.remove(channel);
            if (chSus != null) {
                for (OpenNote n : chSus.values()) {
                    emit(n, sec);
                }
            }
        }

        /** 轨道结束：关闭一切（无踏板延长）。 */
        void flushAll(double sec, boolean unused) {
            for (Integer channel : List.copyOf(open.keySet())) {
                flushChannel(channel, sec);
            }
        }

        void emit(OpenNote n, double endSec) {
            double start = n.startSec;
            double end = Math.max(endSec, start + 0.03);
            double dur = end - start;
            if (tl.minNoteSec < 0 || dur < tl.minNoteSec) {
                tl.minNoteSec = dur;
            }
            if (dur > tl.maxNoteSec) {
                tl.maxNoteSec = dur;
            }
            tl.notes.add(new MidiTimeline.TimelineNote(start, end, n.key, n.velocity, n.channel, n.programKey,
                    tl.isDrumChannel(n.channel), n.gain));
        }

        List<Integer> touchedChannels() {
            return List.copyOf(programs.keySet());
        }
    }

    private static final class OpenNote {
        final int channel;
        final int key;
        final int velocity;
        final int programKey;
        final long startTick;
        final double startSec;
        final float gain;

        OpenNote(int channel, int key, int velocity, int programKey, long startTick, double startSec, float gain) {
            this.channel = channel;
            this.key = key;
            this.velocity = velocity;
            this.programKey = programKey;
            this.startTick = startTick;
            this.startSec = startSec;
            this.gain = gain;
        }
    }
}
