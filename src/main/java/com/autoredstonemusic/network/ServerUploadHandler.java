package com.autoredstonemusic.network;

import com.autoredstonemusic.Log;
import com.autoredstonemusic.data.PendingSongStore;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端上传会话管理。校验大小/分块数/归属，集齐后做完整性校验
 * （总长 + SHA-1 与客户端声明一致）再写入玩家暂存。
 * 任何拒绝都会带完整诊断写入日志，保证测试时能定位到具体环节。
 */
public final class ServerUploadHandler {
    private ServerUploadHandler() {}

    private static final int MAX_TOTAL_BYTES = 25_000_000;
    private static final int MAX_CHUNKS = 2000;
    private static final long SESSION_TIMEOUT_MS = 5 * 60 * 1000L;

    private record Session(String songName, String hash, int totalBytes, byte[][] parts, boolean lamps, long createdAt) {}

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<String, UUID> HASH_TO_PLAYER = new ConcurrentHashMap<>();

    public static void onBegin(UploadBeginPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            Log.warn("onBegin：非玩家来源，忽略");
            return;
        }
        purgeStale();
        if (payload.totalBytes() <= 0 || payload.totalBytes() > MAX_TOTAL_BYTES
                || payload.chunkCount() <= 0 || payload.chunkCount() > MAX_CHUNKS) {
            Log.warn("onBegin：参数越界拒绝 —— name=%s total=%s chunks=%s (限额 %s/%s)".formatted(
                    payload.songName(), payload.totalBytes(), payload.chunkCount(), MAX_TOTAL_BYTES, MAX_CHUNKS));
            player.sendSystemMessage(Component.translatable("message.auto_redstone_music.upload_rejected")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        SESSIONS.put(player.getUUID(), new Session(payload.songName(), payload.hash(), payload.totalBytes(),
                new byte[payload.chunkCount()][], payload.lamps(), System.currentTimeMillis()));
        HASH_TO_PLAYER.put(payload.hash(), player.getUUID());
        Log.info("上传开始: 玩家={} 名称={} 总长={} 分块={} sha1={}",
                player.getName().getString(), payload.songName(), payload.totalBytes(), payload.chunkCount(), payload.hash());
    }

    public static void onChunk(UploadChunkPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        UUID owner = HASH_TO_PLAYER.get(payload.hash());
        Session session = owner == null ? null : SESSIONS.get(owner);
        if (session == null || !owner.equals(player.getUUID()) || payload.index() < 0
                || payload.index() >= session.parts().length) {
            Log.warn("onChunk：无效分块被丢弃 —— hash=%s index=%s 有会话=%s 归属正确=%s".formatted(
                    payload.hash(), payload.index(), session != null, owner != null && owner.equals(player.getUUID())));
            return;
        }
        session.parts()[payload.index()] = payload.data();
        int received = 0;
        for (byte[] part : session.parts()) {
            if (part != null) {
                received++;
            }
        }
        Log.debug("  收到块 {}/{} ({} 字节)", received, session.parts().length, payload.data().length);
        if (received < session.parts().length) {
            return;
        }
        SESSIONS.remove(owner);
        HASH_TO_PLAYER.values().removeIf(owner::equals);

        int total = 0;
        for (byte[] part : session.parts()) {
            total += part.length;
        }
        byte[] bytes = new byte[total];
        int offset = 0;
        for (byte[] part : session.parts()) {
            System.arraycopy(part, 0, bytes, offset, part.length);
            offset += part.length;
        }

        // ---- 完整性校验：总长 + SHA-1 必须与声明一致 ----
        String actualSha1 = Log.sha1(bytes);
        if (total != session.totalBytes() || !actualSha1.equals(session.hash())) {
            Log.error("上传完整性校验失败! 声明: len=%s sha1=%s | 实收: len=%d sha1=%s —— 数据在传输/重组环节被破坏",
                    session.totalBytes(), session.hash(), total, actualSha1);
            player.sendSystemMessage(Component.translatable("message.auto_redstone_music.upload_corrupt")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        Log.info("上传完成并通过校验: {} {}", session.songName(), Log.bytesDiag(bytes));
        PendingSongStore.put(player.getUUID(), session.songName(), bytes, session.lamps());
        player.sendOverlayMessage(Component.translatable("message.auto_redstone_music.applied", session.songName())
                .withStyle(ChatFormatting.GREEN));
        player.sendSystemMessage(Component.translatable("message.auto_redstone_music.place_hint")
                .withStyle(ChatFormatting.YELLOW));
    }

    private static void purgeStale() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Session>> it = SESSIONS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Session> e = it.next();
            if (now - e.getValue().createdAt() > SESSION_TIMEOUT_MS) {
                Log.warn("清理过期上传会话: 玩家=%s 名称=%s", e.getKey(), e.getValue().songName());
                it.remove();
            }
        }
        HASH_TO_PLAYER.values().removeIf(uuid -> !SESSIONS.containsKey(uuid));
    }
}
