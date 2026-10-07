package com.autoredstonemusic.data;

import com.autoredstonemusic.Log;
import com.autoredstonemusic.network.SpeakerSyncPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;

/**
 * 音高通道的客户端同步：放置/拆除时发给正在追踪锚点区块的玩家，登录/换维度时全量补发。
 * data[i] &lt; 0 表示移除该条目。
 */
public final class PitchChannelSync {
    private PitchChannelSync() {}

    private static final int ENTRIES_PER_PAYLOAD = 2000;

    /** 把通道变化发给正在追踪 anchor 所在区块的玩家。 */
    public static void sendToTracking(ServerLevel level, BlockPos anchor, Map<Long, Long> entries, boolean remove) {
        if (entries.isEmpty()) {
            return;
        }
        int n = entries.size();
        long[] positions = new long[n];
        long[] data = new long[n];
        int i = 0;
        for (Map.Entry<Long, Long> e : entries.entrySet()) {
            positions[i] = e.getKey();
            data[i] = remove ? -1L : e.getValue();
            i++;
        }
        PacketDistributor.sendToPlayersTrackingChunk(level, new ChunkPos(anchor.getX() >> 4, anchor.getZ() >> 4),
                new SpeakerSyncPayload(positions, data));
        Log.debug("音高通道同步({}): {} 条 -> 追踪玩家", remove ? "移除" : "登记", n);
    }

    /** 登录/换维度：全量补发该维度的通道（分块）。 */
    public static void sendAllTo(ServerLevel level, net.minecraft.server.level.ServerPlayer player) {
        Map<Long, Long> snapshot = PitchChannel.snapshot(level);
        if (snapshot.isEmpty()) {
            return;
        }
        long[] allPos = new long[snapshot.size()];
        long[] allData = new long[snapshot.size()];
        int i = 0;
        for (Map.Entry<Long, Long> e : snapshot.entrySet()) {
            allPos[i] = e.getKey();
            allData[i] = e.getValue();
            i++;
        }
        for (int from = 0; from < allPos.length; from += ENTRIES_PER_PAYLOAD) {
            int to = Math.min(allPos.length, from + ENTRIES_PER_PAYLOAD);
            int len = to - from;
            long[] positions = new long[len];
            long[] data = new long[len];
            System.arraycopy(allPos, from, positions, 0, len);
            System.arraycopy(allData, from, data, 0, len);
            PacketDistributor.sendToPlayer(player, new SpeakerSyncPayload(positions, data));
        }
        Log.debug("音高通道全量同步给 {}: {} 条", player.getName().getString(), allPos.length);
    }
}
