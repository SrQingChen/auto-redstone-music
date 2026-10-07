package com.autoredstonemusic.block;

import com.autoredstonemusic.ModRegistries;
import com.autoredstonemusic.blockentity.MusicControllerBlockEntity;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * 音乐控制器：区域的"磁带机"。空手右键 = 启动/停止；潜行右键（IDLE 时）= 整区拆除；
 * 构建中/拆除中右键 = 汇报进度。播放本体是原版红石链，控制器只负责启停与玩家搬运。
 */
public class MusicControllerBlock extends BaseEntityBlock {
    public static final MapCodec<MusicControllerBlock> CODEC = simpleCodec(MusicControllerBlock::new);

    private static final VoxelShape SHAPE = box(1, 0, 1, 15, 14, 15);

    public MusicControllerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MusicControllerBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return createTickerHelper(type, ModRegistries.MUSIC_CONTROLLER_BE.get(), MusicControllerBlockEntity::serverTick);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.getBlockEntity(pos) instanceof MusicControllerBlockEntity controller) {
            if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
                controller.onUse(serverPlayer, player.isShiftKeyDown());
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

}
