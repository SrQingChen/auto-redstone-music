package com.autoredstonemusic.arrange;

import com.autoredstonemusic.Config;
import com.autoredstonemusic.arrange.SongArrangement.ArrangedNote;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 红石链编译器 v0.2（逐音精确刻链）：把编排好的音符编译成每条轨道的红石器件序列。
 *
 * <p><b>时间体系</b>：没有固定栅格。每个音符的触发时刻 = round(绝对秒×20) 游戏刻（≤25ms 误差，不累积），
 * 由"精密中继器"（1-{@value Config#REPEATER_MAX_DELAY_TICKS} 游戏刻任意延迟）逐音编码：
 * 链结构 = [启动源S] [精中继器 d=t1] [红线D1] [精中继器 d=t2-t1] [红线D2] ...
 * D_j 在启动后第 t_j 刻通电——与服务器 tick 同源、绝对精确。BPM 曲线/曲中变速/自由速度全部
 * 直接烙进延迟序列，变速体现为隧道内音符密度变化。
 *
 * <p><b>触发</b>：音符盒置于"枝路中继器"输出端（枝路中继器由该音符之前最近的一个全局到达红线
 * 馈电，延迟 = 音符刻 - 馈电刻 >= 1）——两音间隔无论多近（>=1 刻）都能精确补偿。
 * 全局到达间隔超过精中继器量程时插入空到达点（无音符的红线）。
 *
 * <p><b>几何</b>：每个全局到达点占 2 格（精中继器+红线），行进速度随音乐密度变化——玩家载具
 * 动态跟随波前（速度的呼吸就是音乐本身的呼吸）。编译后 validate 逐音自检。
 */
public final class ChainCompiler {
    private ChainCompiler() {}

    public enum Kind { SOURCE, DUST, REPEATER }

    /** DUST 单元上挂的侧枝音符（side: -1/+1；delayGt = 音符刻 - 馈电刻）。 */
    public record SideNote(int side, ArrangedNote note, int delayGt) {}

    /** 链单元。world 坐标 = 轨道线中心 + facing*(z + CHAIN_START_OFFSET)。 */
    public static final class ChainCell {
        public final Kind kind;
        public final int z;
        public final int delay;      // REPEATER：游戏刻
        public final int tick;       // DUST：通电时刻（距启动的刻数）
        public final List<SideNote> sideNotes;

        ChainCell(Kind kind, int z, int delay, int tick, List<SideNote> sideNotes) {
            this.kind = kind;
            this.z = z;
            this.delay = delay;
            this.tick = tick;
            this.sideNotes = sideNotes;
        }
    }

    public static final class CompiledSong {
        public final int[] arrivalTicks; // 全局到达时刻（升序）
        public final int[] arrivalZ;     // 对应链 z（世界偏移后）
        public final int maxZ;
        public final List<List<ChainCell>> lineCells;

        CompiledSong(int[] arrivalTicks, int[] arrivalZ, int maxZ, List<List<ChainCell>> lineCells) {
            this.arrivalTicks = arrivalTicks;
            this.arrivalZ = arrivalZ;
            this.maxZ = maxZ;
            this.lineCells = lineCells;
        }
    }

    public static CompiledSong compile(SongArrangement arrangement) {
        int lineCount = arrangement.lines.size();
        TreeSet<Integer> noteTicks = new TreeSet<>();
        for (SongArrangement.ArrangedLine line : arrangement.lines) {
            for (ArrangedNote note : line.notes) {
                noteTicks.add(note.tick());
            }
        }
        if (noteTicks.isEmpty()) {
            throw new IllegalStateException("编排中没有可放置的音符");
        }

        // 全局到达时刻：音符刻 + 填充点（间隔 <= 量程）
        TreeSet<Integer> arrivals = new TreeSet<>();
        int prev = 0;
        for (int t : noteTicks) {
            while (t - prev > Config.REPEATER_MAX_DELAY_TICKS) {
                prev += Config.REPEATER_MAX_DELAY_TICKS;
                arrivals.add(prev);
            }
            arrivals.add(t);
            prev = t;
        }

        // ---- 模板链：每个到达点 [精中继器 d=delta][红线] ----
        List<ChainCell> template = new ArrayList<>();
        int z = Config.CHAIN_START_OFFSET - 1;
        template.add(new ChainCell(Kind.SOURCE, z, 0, 0, List.of()));
        int[] arrivalTicks = new int[arrivals.size()];
        int[] arrivalZ = new int[arrivals.size()];
        int j = 0;
        int prevTick = 0;
        for (int t : arrivals) {
            // 每个到达点都由一颗精中继器馈电（首个到达由 SOURCE 馈电，t1 >= 1 保证延迟 >=1）
            template.add(new ChainCell(Kind.REPEATER, ++z, t - prevTick, -1, List.of()));
            template.add(new ChainCell(Kind.DUST, ++z, 0, t, new ArrayList<>()));
            arrivalTicks[j] = t;
            arrivalZ[j] = z + Config.CHAIN_START_OFFSET;
            prevTick = t;
            j++;
        }
        int maxZ = z + Config.CHAIN_START_OFFSET;

        // ---- 按线挂载：侧枝挂在"该音符之前最近的全局到达"上（SOURCE 视作 tick 0）----
        List<List<ChainCell>> lineCells = new ArrayList<>(lineCount);
        for (int lineIdx = 0; lineIdx < lineCount; lineIdx++) {
            SongArrangement.ArrangedLine line = arrangement.lines.get(lineIdx);
            java.util.Map<Integer, List<SideNote>> sideAt = new java.util.HashMap<>();
            for (ArrangedNote note : line.notes) {
                int feederIdx = lowerArrival(arrivalTicks, note.tick());
                int feederTick = feederIdx < 0 ? 0 : arrivalTicks[feederIdx];
                int delay = note.tick() - feederTick;
                if (delay < 1) {
                    throw new IllegalStateException("枝路延迟 <1 刻（tick=" + note.tick() + "）");
                }
                int key = feederIdx < 0 ? 0 : arrivalTicks[feederIdx];
                sideAt.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new SideNote(note.trigger() == SongArrangement.TRIGGER_LEFT ? -1 : 1, note, delay));
            }
            List<ChainCell> cells = new ArrayList<>(template.size());
            for (ChainCell t : template) {
                if (t.kind == Kind.DUST) {
                    List<SideNote> sides = sideAt.getOrDefault(t.tick, List.of());
                    if (sides.size() > Config.SIDE_CAPACITY) {
                        throw new IllegalStateException("侧枝超容量 tick=" + t.tick);
                    }
                    cells.add(new ChainCell(Kind.DUST, t.z, 0, t.tick, List.copyOf(sides)));
                } else {
                    cells.add(t);
                }
            }
            lineCells.add(cells);
        }

        CompiledSong compiled = new CompiledSong(arrivalTicks, arrivalZ, maxZ, lineCells);
        try {
            validate(arrangement, compiled);
        } catch (IllegalStateException e) {
            com.autoredstonemusic.Log.error("红石链编译自检失败! 线数=" + lineCount, e);
            throw e;
        }
        com.autoredstonemusic.Log.info("红石链编译通过（逐音刻链）: 线 {} 音符 {} 到达点 {} 链尾 z={}",
                lineCount, arrangement.totalNotes, arrivalTicks.length, maxZ);
        return compiled;
    }

    /** 最大的到达时刻下标使 arrivalTicks[idx] < tick；无则 -1（馈电=SOURCE，tick 0）。 */
    private static int lowerArrival(int[] arrivalTicks, int tick) {
        int lo = 0;
        int hi = arrivalTicks.length - 1;
        int ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (arrivalTicks[mid] < tick) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    /** 编译自检：逐音断言 触发刻 === 精确刻（累加链延迟），音符全覆盖。 */
    static void validate(SongArrangement arrangement, CompiledSong compiled) {
        for (int lineIdx = 0; lineIdx < compiled.lineCells.size(); lineIdx++) {
            List<ChainCell> cells = compiled.lineCells.get(lineIdx);
            long cum = 0;
            boolean first = true;
            int counted = 0;
            for (ChainCell cell : cells) {
                switch (cell.kind) {
                    case SOURCE -> check(first, "SOURCE 必须在链首");
                    case REPEATER -> {
                        check(cell.delay >= 1 && cell.delay <= Config.REPEATER_MAX_DELAY_TICKS,
                                "精中继器延迟越界：" + cell.delay);
                        cum += cell.delay;
                    }
                    case DUST -> {
                        check(cum == cell.tick, "到达时刻失配：累积=" + cum + " 期望=" + cell.tick);
                        for (SideNote side : cell.sideNotes) {
                            check(side.delayGt() + cum == side.note().tick(),
                                    "枝路触发失配：馈电 " + cum + " + 延迟 " + side.delayGt()
                                            + " != 音符刻 " + side.note().tick());
                            counted++;
                        }
                    }
                }
                first = false;
            }
        }
        for (int i = 1; i < compiled.arrivalTicks.length; i++) {
            check(compiled.arrivalTicks[i] > compiled.arrivalTicks[i - 1], "到达时刻必须严格递增");
            check(compiled.arrivalZ[i] - compiled.arrivalZ[i - 1] == 2, "到达点间距必须为 2 格");
        }
    }

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new IllegalStateException(msg);
        }
    }
}
