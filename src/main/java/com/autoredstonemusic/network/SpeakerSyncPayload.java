package com.autoredstonemusic.network;

import com.autoredstonemusic.AutoRedstoneMusicMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * S→C：音高通道同步。positions[i] = BlockPos.asLong()，data[i] = 打包参数（<0 表示移除该条）。
 * 放置/拆除/玩家登录时发送，客户端写入 {@link com.autoredstonemusic.data.ClientPitchStore}。
 */
public record SpeakerSyncPayload(long[] positions, long[] data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SpeakerSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(AutoRedstoneMusicMod.MODID, "speaker_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SpeakerSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.LONG_ARRAY, SpeakerSyncPayload::positions,
            ByteBufCodecs.LONG_ARRAY, SpeakerSyncPayload::data,
            SpeakerSyncPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
