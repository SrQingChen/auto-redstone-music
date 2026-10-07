package com.autoredstonemusic.client;

/** 客户端录制缓存：上传编排时写入（歌曲名 + 总刻数，用于录制时长）。 */
public final class ClientCinemaCache {
    private ClientCinemaCache() {}

    public static String songName = "";
    public static int totalTicks;
}
