package com.autoredstonemusic.data;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "应用 → 放置"之间的服务端暂存（内存态，按玩家）。
 * UI 上传完成时写入；右键方块放置时取出；登出/超时清理由使用方负责。
 */
public final class PendingSongStore {
    private PendingSongStore() {}

    public record PendingSong(String name, byte[] bytes, boolean lamps) {}

    private record Dated(PendingSong song, long createdAt) {}

    private static final long TIMEOUT_MS = 10 * 60 * 1000L;
    private static final Map<UUID, Dated> PENDING = new ConcurrentHashMap<>();

    public static void put(UUID player, String name, byte[] bytes, boolean lamps) {
        PENDING.put(player, new Dated(new PendingSong(name, bytes, lamps), System.currentTimeMillis()));
    }

    /** 取出并清除（过期返回 null）。 */
    public static PendingSong consume(UUID player) {
        Dated dated = PENDING.remove(player);
        if (dated == null) {
            return null;
        }
        if (System.currentTimeMillis() - dated.createdAt() > TIMEOUT_MS) {
            return null;
        }
        return dated.song();
    }

    public static void clear(UUID player) {
        PENDING.remove(player);
    }
}
