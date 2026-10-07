package com.autoredstonemusic;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 自动红石音乐：MIDI → 自动编配 → 圆柱形原版红石音乐隧道。
 * 架构与时间体系见 开发规划.md 与 ChainCompiler 的类注释。
 */
@Mod(AutoRedstoneMusicMod.MODID)
public class AutoRedstoneMusicMod {
    public static final String MODID = "auto_redstone_music";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AutoRedstoneMusicMod(IEventBus modEventBus, ModContainer modContainer) {
        ModRegistries.register(modEventBus);
        com.autoredstonemusic.registry.ModSounds.register(modEventBus);
        com.autoredstonemusic.network.ModNetworking.register(modEventBus);
        modEventBus.addListener(this::commonSetup);
        // 登录/换维度：把该维度已铺设音符盒的音高通道同步给客户端（Mixin 拦截需要）
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player
                    && player.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                com.autoredstonemusic.data.PitchChannelSync.sendAllTo(serverLevel, player);
            }
        });
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            Path midiDir = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve(Config.MIDI_DIR);
            try {
                Files.createDirectories(midiDir);
                com.autoredstonemusic.Log.reinit(net.neoforged.fml.loading.FMLPaths.GAMEDIR.get());
                com.autoredstonemusic.Log.info("MIDI 目录就绪：{}", midiDir);
                com.autoredstonemusic.Log.info("详尽调试：{}（开启方式：启动参数 -Darm.debug=true，或在 midi 文件夹放 .debug 空文件）",
                        com.autoredstonemusic.Log.verbose() ? "开" : "关");
            } catch (IOException e) {
                LOGGER.error("无法创建 MIDI 目录", e);
            }
        });
    }
}
