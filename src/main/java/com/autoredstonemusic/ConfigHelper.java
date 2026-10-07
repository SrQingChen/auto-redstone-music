package com.autoredstonemusic;

import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/** 路径助手。 */
public final class ConfigHelper {
    private ConfigHelper() {}

    /** 摄像机模式视频输出目录（游戏根目录/红石音乐视频）。 */
    public static Path videoDir() {
        return FMLPaths.GAMEDIR.get().resolve("红石音乐视频");
    }
}
