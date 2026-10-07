package com.autoredstonemusic.world;

import com.autoredstonemusic.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 区域几何：把"编排 + 链编译"的抽象坐标映射到世界方块坐标。
 *
 * <p><b>布局（v0.1.2，用户定稿）</b>：弃用圆形，改为"两侧 + 下一层"两层共 17 个位置，按由近到远排列：
 * <ol>
 *   <li>玩家身高层（dy 0）：左右各 4 列（横向 ±3/±8/±13/±18）——8 条；</li>
 *   <li>下一层（dy -3）：左右各 4 列（横向 ±5/±10/±15/±20）——8 条；</li>
 *   <li>正下方 1 列（横向 0，dy -3）——1 条。</li>
 * </ol>
 * 编排器按"响度（音符速度总和）降序"分配位置：最响的轨道贴着玩家，安静的放远处/下层。
 * 相邻列中心间距 ≥5 格（走廊半宽 2 + 间隙 1），层间垂直错开（上层基座 -1，下层机械 -3），互不重叠。
 *
 * <p><b>坐标系</b>：右键点 = 起点截面的圆心（origin，也是控制器方块的位置）；
 * 轴向 = 玩家水平朝向（facing）；lat 是垂直于 facing 的水平方向（玩家右手侧为 +lat）。
 */
public final class RegionGeometry {
    /** 17 个位置：{横向偏移(沿 lat，格), 垂直偏移(dy)}，由近到远（用户定稿：8+8+1=17）：
     *  玩家层左右各 4 列（±3/±8/±13/±18）→ 下一层左右各 4 列（±5/±10/±15/±20）→ 正下方 1 列。 */
    private static final int[][] POSITIONS = {
            {3, 0}, {-3, 0}, {8, 0}, {-8, 0}, {13, 0}, {-13, 0}, {18, 0}, {-18, 0},
            {5, -3}, {-5, -3}, {10, -3}, {-10, -3}, {15, -3}, {-15, -3}, {20, -3}, {-20, -3},
            {0, -3}
    };

    public static final int MAX_SUPPORTED_LINES = POSITIONS.length;

    /** 载具/残留清扫盒的膨胀边距（覆盖最远走廊 + 余量）。 */
    public static final int SWEEP_MARGIN = 24;

    public final BlockPos origin;
    public final Direction facing;
    public final int lineCount;
    public final int latX;
    public final int latZ;
    public final Direction latDirection; // +lateral 方向

    public RegionGeometry(BlockPos origin, Direction facing, int lineCount) {
        this.origin = origin.immutable();
        this.facing = facing;
        this.lineCount = Math.max(1, Math.min(lineCount, MAX_SUPPORTED_LINES));
        this.latX = -facing.getStepZ();
        this.latZ = facing.getStepX();
        this.latDirection = latX > 0 ? Direction.EAST
                : latX < 0 ? Direction.WEST
                : latZ > 0 ? Direction.SOUTH
                : latZ < 0 ? Direction.NORTH : Direction.SOUTH;
    }

    /** 第 line 条线的走廊中心（机械层高度）。 */
    public BlockPos lineCenter(int line) {
        int[] pos = POSITIONS[Math.min(line, POSITIONS.length - 1)];
        return origin.offset(latX * pos[0], pos[1], latZ * pos[0]);
    }

    /** 链单元的世界坐标：chainZ=0 是启动源（世界偏移 = chainZ + 1，z=0 留给控制器）。 */
    public BlockPos cell(int line, int chainZ, int lateral, int dy) {
        return lineCenter(line)
                .relative(latDirection, lateral)
                .relative(facing, chainZ + Config.CHAIN_START_OFFSET)
                .above(dy);
    }

    /** slot → 世界轴向偏移（玩家跟随/引擎 B 波前用）。 */
    public BlockPos axisPos(int worldZ, int dy) {
        return origin.relative(facing, worldZ).above(dy);
    }

    /** 链上中继器的 FACING（26.1.2 语义：FACING 指向输入侧，输出在反方向）。 */
    public Direction chainRepeaterFacing() {
        return facing.getOpposite();
    }

    /** 侧枝中继器的 FACING：输入来自中央红线，输出朝向 ±lat 上的音符盒。 */
    public Direction sideRepeaterFacing(int side) {
        return side > 0 ? latDirection.getOpposite() : latDirection;
    }

    /**
     * 该线音符盒的侧向镜像符号（观感对称，用户定稿）：
     * 位于隧道【右侧】的线（横向 > 0）返回 -1——第一个音符放到轨道右侧；
     * 位于【左侧】的线（横向 < 0）返回 +1——第一个音符保持放轨道左侧；
     * 正下方中线（横向 0）返回 +1。仅影响摆放呈现，编排数据（逻辑左右侧）不变。
     */
    public int noteSideSign(int line) {
        int[] pos = POSITIONS[Math.min(Math.max(line, 0), POSITIONS.length - 1)];
        return pos[0] > 0 ? -1 : 1;
    }
}
