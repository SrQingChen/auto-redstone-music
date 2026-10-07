package com.autoredstonemusic.data;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端音高通道镜像（服务端经 SpeakerSyncPayload 同步）。
 * Mixin 在客户端用它判断"是否取消原版音符盒发声"（自定义声由服务端广播送达）。
 * 仅客户端加载；键 = BlockPos.asLong()（跨维度混用概率极低，接受）。
 */
public final class ClientPitchStore {
    private ClientPitchStore() {}

    private static final Map<Long, Long> ENTRIES = new ConcurrentHashMap<>();

    public static void apply(long[] positions, long[] data) {
        for (int i = 0; i < positions.length && i < data.length; i++) {
            if (data[i] < 0) {
                ENTRIES.remove(positions[i]);
            } else {
                ENTRIES.put(positions[i], data[i]);
            }
        }
    }

    public static Long get(long pos) {
        return ENTRIES.get(pos);
    }
}
