package com.autoredstonemusic.world;

import com.autoredstonemusic.Config;
import com.autoredstonemusic.ModRegistries;
import com.autoredstonemusic.arrange.ChainCompiler;
import com.autoredstonemusic.arrange.InstrumentMap;
import com.autoredstonemusic.arrange.SongArrangement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;
import com.autoredstonemusic.block.PrecisionRepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 区域写入计划：把"超大立方体清空 → 玻璃栈道 → 逐线铺设 → 控制器"展开为确定性的方块写入序列。
 *
 * <p>按游标惰性产出（{@link #next()} 每次返回下一个写入，O(1) 额外内存），
 * 由控制器 BlockEntity 每刻限量执行，便于超长乐曲分批放置并汇报进度。
 * 清拆模式（{@link #removal}）走同一遍历但全部写 AIR，且不含控制器格——
 * 控制器由调用方最后 removeBlock（避免在自身 tick 内销毁自己）。
 *
 * <p><b>清空包络（用户定稿）</b>：不论实际轨道数，一律按"满轨道（最远 ±20）"情况取
 * 超大立方体并四周外扩 2 格：纬向 ±{@link Config#CLEAR_LATERAL_HALF}、
 * 垂直 {CLEAR_DY_MIN}..{CLEAR_DY_MAX}、轴向 -2..链尾+2。避免任何残留方块干扰轨道。
 * 遍历顺序：轴向外层 → 纬向 → 垂直（沿隧道向前掘进，与区块异步加载节奏吻合）。
 */
public final class RegionPlanner {
    private enum Phase { CLEAR_BOX, FLOOR, LINES, CONTROLLER, DONE }

    /** 一次方块写入。 */
    public record Write(BlockPos pos, BlockState state) {}

    private final RegionGeometry geo;
    private final ChainCompiler.CompiledSong compiled;
    private final boolean removal;
    /** 是否在音符盒上方添加红石灯。 */
    private final boolean lamps;
    private final int maxWorldZ;

    private Phase phase = Phase.CLEAR_BOX;
    private int boxZ, boxLat, boxDy;        // CLEAR_BOX 游标（世界轴向偏移/纬向/垂直）
    private int cZ;                         // FLOOR 游标
    private int line, cellIdx, subIdx;      // LINES 游标
    private List<ChainCompiler.ChainCell> currentCells = List.of();
    private List<Write> currentCellWrites = List.of();

    private final Map<String, BlockState> underBlockCache = new HashMap<>();
    private final int total;
    private int done;

    private RegionPlanner(RegionGeometry geo, ChainCompiler.CompiledSong compiled, boolean removal, boolean lamps) {
        this.geo = geo;
        this.compiled = compiled;
        this.removal = removal;
        this.lamps = lamps;
        this.maxWorldZ = (compiled != null ? compiled.maxZ : 0) + 2;
        this.total = computeTotal();
        this.boxZ = -Config.CLEAR_AXIAL_MARGIN;
    }

    /** 放置模式。 */
    public static RegionPlanner build(RegionGeometry geo, SongArrangement arrangement,
                                      ChainCompiler.CompiledSong compiled, boolean lamps) {
        return new RegionPlanner(geo, compiled, false, lamps);
    }

    /** 清拆模式：仅按包络清空（不需要编配数据）。 */
    public static RegionPlanner removal(RegionGeometry geo) {
        return new RegionPlanner(geo, null, true, false);
    }

    public int totalWrites() {
        return total;
    }

    public int doneWrites() {
        return done;
    }

    public boolean isDone() {
        return phase == Phase.DONE;
    }

    private int computeTotal() {
        long boxLen = (long) (maxWorldZ + Config.CLEAR_AXIAL_MARGIN) - (-Config.CLEAR_AXIAL_MARGIN) + 1;
        long boxWide = Config.CLEAR_LATERAL_HALF * 2L + 1;
        long boxTall = Config.CLEAR_DY_MAX - (long) Config.CLEAR_DY_MIN + 1;
        long total = boxLen * boxWide * boxTall - 1; // 超大立方体清空（跳过控制器自身格）
        if (!removal) {
            total += maxWorldZ + 1;              // 玻璃栈道
            if (compiled != null) {
                for (List<ChainCompiler.ChainCell> cells : compiled.lineCells) {
                    for (ChainCompiler.ChainCell cell : cells) {
                        total += estimateCellWrites(cell);
                    }
                }
            }
            total += 1;                          // 控制器
        }
        return (int) Math.min(total, Integer.MAX_VALUE);
    }

    private int estimateCellWrites(ChainCompiler.ChainCell cell) {
        return switch (cell.kind) {
            case SOURCE -> 0;
            case REPEATER -> 2;
            case DUST -> 2 + cell.sideNotes.size() * (lamps ? 5 : 4);
        };
    }

    /** 下一个写入。调用前必须 !isDone()。 */
    public Write next() {
        while (true) {
            switch (phase) {
                case CLEAR_BOX -> {
                    int zMax = maxWorldZ + Config.CLEAR_AXIAL_MARGIN;
                    if (boxZ > zMax) {
                        if (removal) {
                            phase = Phase.DONE;
                            continue;
                        }
                        phase = Phase.FLOOR;
                        cZ = 0;
                        continue;
                    }
                    if (boxLat > Config.CLEAR_LATERAL_HALF) {
                        boxZ++;                 // 下一圈（轴向推进一格，纬向重扫）
                        boxLat = -Config.CLEAR_LATERAL_HALF;
                        boxDy = Config.CLEAR_DY_MIN;
                        continue;
                    }
                    if (boxDy > Config.CLEAR_DY_MAX) {
                        boxLat++;
                        boxDy = Config.CLEAR_DY_MIN;
                        continue;
                    }
                    BlockPos cell = geo.origin.relative(geo.facing, boxZ)
                            .offset(geo.latX * boxLat, boxDy, geo.latZ * boxLat);
                    boxDy++;
                    if (cell.equals(geo.origin)) {
                        // 关键：绝不清除控制器自身格 —— 否则首次构建 tick 控制器自毁、
                        // BlockEntity 停转，整个铺设静默中断（v0.1.1 实测踩坑）。
                        continue;
                    }
                    done++;
                    return new Write(cell, Blocks.AIR.defaultBlockState());
                }
                case FLOOR -> {
                    if (cZ > maxWorldZ) {
                        phase = Phase.LINES;
                        line = 0;
                        cellIdx = 0;
                        subIdx = 0;
                        currentCells = compiled.lineCells.get(0);
                        currentCellWrites = List.of();
                        continue;
                    }
                    Write w = new Write(geo.axisPos(cZ, -1), Blocks.GLASS.defaultBlockState());
                    cZ++;
                    done++;
                    return w;
                }
                case LINES -> {
                    if (line >= geo.lineCount) {
                        phase = Phase.CONTROLLER;
                        continue;
                    }
                    if (cellIdx >= currentCells.size()) {
                        line++;
                        cellIdx = 0;
                        subIdx = 0;
                        currentCells = line < compiled.lineCells.size() ? compiled.lineCells.get(line) : List.of();
                        continue;
                    }
                    if (subIdx >= currentCellWrites.size()) {
                        currentCellWrites = cellWrites(currentCells.get(cellIdx));
                        cellIdx++;
                        subIdx = 0;
                        continue;
                    }
                    Write w = currentCellWrites.get(subIdx);
                    subIdx++;
                    done++;
                    return w;
                }
                case CONTROLLER -> {
                    phase = Phase.DONE;
                    done++;
                    return new Write(geo.origin, ModRegistries.MUSIC_CONTROLLER.get().defaultBlockState());
                }
                case DONE -> {
                    return null;
                }
            }
        }
    }

    /** 为一个链单元生成它的全部写入（基座先行，音符在后，保证 updateShape 推导顺序正确）。 */
    private List<Write> cellWrites(ChainCompiler.ChainCell cell) {
        return switch (cell.kind) {
            case SOURCE -> List.of();
            case REPEATER -> List.of(
                    new Write(geo.cell(line, cell.z, 0, -1), railState()),
                    new Write(geo.cell(line, cell.z, 0, 0), precisionRepeaterState(geo.chainRepeaterFacing(), cell.delay))
            );
            case DUST -> {
                List<Write> writes = new ArrayList<>(2 + cell.sideNotes.size() * (lamps ? 5 : 4));
                writes.add(new Write(geo.cell(line, cell.z, 0, -1), railState()));
                writes.add(new Write(geo.cell(line, cell.z, 0, 0), Blocks.REDSTONE_WIRE.defaultBlockState()));
                // 右侧线镜像摆放（观感对称）：逻辑侧 × 镜像符号 = 实际摆放侧
                int mirror = geo.noteSideSign(line);
                for (ChainCompiler.SideNote side : cell.sideNotes) {
                    int eff = side.side() * mirror;
                    writes.add(new Write(geo.cell(line, cell.z, eff, -1), railState()));
                    writes.add(new Write(geo.cell(line, cell.z, eff, 0),
                            precisionRepeaterState(geo.sideRepeaterFacing(eff), side.delayGt())));
                    writes.add(new Write(geo.cell(line, cell.z, eff * 2, -1), underBlockState(side.note())));
                    writes.add(new Write(geo.cell(line, cell.z, eff * 2, 0), noteState(side.note())));
                    if (lamps) {
                        // 音符盒上方一格（清空后必为空气）
                        writes.add(new Write(geo.cell(line, cell.z, eff * 2, 1),
                                Blocks.REDSTONE_LAMP.defaultBlockState()));
                    }
                }
                yield writes;
            }
        };
    }

    private BlockState railState() {
        return ModRegistries.TRACK_RAIL.get().defaultBlockState();
    }

    private BlockState precisionRepeaterState(Direction facing, int delayTicks) {
        return ModRegistries.PRECISION_REPEATER.get().defaultBlockState()
                .setValue(PrecisionRepeaterBlock.FACING, facing)
                .setValue(PrecisionRepeaterBlock.DELAY_TICKS,
                        Math.max(1, Math.min(Config.REPEATER_MAX_DELAY_TICKS, delayTicks)));
    }

    private BlockState noteState(SongArrangement.ArrangedNote note) {
        NoteBlockInstrument instrument = InstrumentMap.bySerializedName(note.instrument());
        return Blocks.NOTE_BLOCK.defaultBlockState()
                .setValue(NoteBlock.NOTE, note.pitch())
                .setValue(NoteBlock.INSTRUMENT, instrument)
                .setValue(NoteBlock.POWERED, false);
    }

    // ---- 清扫用静态状态构造器（控制器 BE 复用）----

    public static BlockState wireOffState() {
        return Blocks.REDSTONE_WIRE.defaultBlockState();
    }

    public static BlockState precisionRepeaterOffState(Direction facing, int delayTicks) {
        return ModRegistries.PRECISION_REPEATER.get().defaultBlockState()
                .setValue(PrecisionRepeaterBlock.FACING, facing)
                .setValue(PrecisionRepeaterBlock.DELAY_TICKS,
                        Math.max(1, Math.min(Config.REPEATER_MAX_DELAY_TICKS, delayTicks)));
    }

    public static BlockState noteOffState(SongArrangement.ArrangedNote note) {
        NoteBlockInstrument instrument = InstrumentMap.bySerializedName(note.instrument());
        return Blocks.NOTE_BLOCK.defaultBlockState()
                .setValue(NoteBlock.NOTE, note.pitch())
                .setValue(NoteBlock.INSTRUMENT, instrument)
                .setValue(NoteBlock.POWERED, false);
    }

    private BlockState underBlockState(SongArrangement.ArrangedNote note) {
        return underBlockCache.computeIfAbsent(note.instrument(), id -> {
            String blockId = InstrumentMap.underBlockFor(id);
            if (blockId.isEmpty()) {
                return railState();
            }
            Block block = BuiltInRegistries.BLOCK.get(Identifier.parse(blockId)).map(h -> h.value()).orElse(null);
            return block != null ? block.defaultBlockState() : railState();
        });
    }
}
