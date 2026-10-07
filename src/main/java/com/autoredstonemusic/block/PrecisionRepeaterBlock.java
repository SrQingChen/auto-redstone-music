package com.autoredstonemusic.block;

import com.autoredstonemusic.Config;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * 精密中继器：外观与原版中继器一致，但延迟以【游戏刻】为单位、可 1-{@value Config#REPEATER_MAX_DELAY_TICKS}
 * 刻任意设定（原版固定 2/4/6/8 刻）。用于把 MIDI 的精确节奏（每音间隔的刻数）烙进红石链——
 * 通断调度完全复用 DiodeBlock 的通用二极管机制，本类只提供以刻为单位的 getDelay。
 * 链上更长的间隔由多颗串联（每颗至多 {@value Config#REPEATER_MAX_DELAY_TICKS} 刻）表达。
 */
public class PrecisionRepeaterBlock extends DiodeBlock {
    public static final MapCodec<PrecisionRepeaterBlock> CODEC = simpleCodec(PrecisionRepeaterBlock::new);
    public static final IntegerProperty DELAY_TICKS = IntegerProperty.create("delay_ticks", 1, Config.REPEATER_MAX_DELAY_TICKS);

    public PrecisionRepeaterBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(DELAY_TICKS, 1)
                .setValue(POWERED, false));
    }

    @Override
    public MapCodec<PrecisionRepeaterBlock> codec() {
        return CODEC;
    }

    @Override
    protected int getDelay(BlockState state) {
        return state.getValue(DELAY_TICKS); // 直接以游戏刻为单位（原版是 DELAY×2）
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HorizontalDirectionalBlock.FACING, DELAY_TICKS, BlockStateProperties.POWERED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // 与原版中继器一致：FACING 指向输入侧（反方向为输出）
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }
}
