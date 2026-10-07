package com.autoredstonemusic.item;

import com.autoredstonemusic.blockentity.MusicControllerBlockEntity;
import com.autoredstonemusic.client.gui.MidiBrowserScreen;
import com.autoredstonemusic.data.PendingSongStore;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * 生成器：Shift+右键（方块或空气）打开 MIDI 浏览器；应用后右键方块放置红石音乐区域。
 * 破坏性清空仅创造模式允许（v1 策略）。
 */
public class GeneratorItem extends Item {

    public GeneratorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (player.isShiftKeyDown()) {
            if (context.getLevel().isClientSide()) {
                openScreen();
            }
            return InteractionResult.SUCCESS;
        }
        if (context.getLevel() instanceof ServerLevel level && player instanceof ServerPlayer serverPlayer) {
            PendingSongStore.PendingSong pending = PendingSongStore.consume(serverPlayer.getUUID());
            if (pending == null) {
                serverPlayer.sendOverlayMessage(Component.translatable("message.auto_redstone_music.no_pending")
                        .withStyle(ChatFormatting.RED));
                serverPlayer.sendSystemMessage(Component.translatable("message.auto_redstone_music.ui_hint")
                        .withStyle(ChatFormatting.GRAY));
                return InteractionResult.SUCCESS;
            }
            if (!player.isCreative()) {
                serverPlayer.sendSystemMessage(Component.translatable("message.auto_redstone_music.permission")
                        .withStyle(ChatFormatting.RED));
                PendingSongStore.put(serverPlayer.getUUID(), pending.name(), pending.bytes(), pending.lamps()); // 归还暂存
                return InteractionResult.SUCCESS;
            }
            MusicControllerBlockEntity.placeRegion(level, serverPlayer, context.getClickedPos(), pending.name(), pending.bytes(), pending.lamps());
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player.isShiftKeyDown()) {
            if (level.isClientSide()) {
                openScreen();
            }
            return InteractionResult.SUCCESS;
        }
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendOverlayMessage(Component.translatable("message.auto_redstone_music.ui_hint")
                    .withStyle(ChatFormatting.GRAY));
        }
        return InteractionResult.SUCCESS;
    }

    private void openScreen() {
        net.minecraft.client.Minecraft.getInstance().setScreen(new MidiBrowserScreen());
    }
}
