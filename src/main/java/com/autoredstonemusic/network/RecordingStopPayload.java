package com.autoredstonemusic.network;

import com.autoredstonemusic.AutoRedstoneMusicMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** S→C：播放已停止（自然结束或手动），客户端立即结束录制并保存视频。 */
public record RecordingStopPayload() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RecordingStopPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(AutoRedstoneMusicMod.MODID, "recording_stop"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RecordingStopPayload> STREAM_CODEC =
            StreamCodec.of((buf, payload) -> {
            }, buf -> new RecordingStopPayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
