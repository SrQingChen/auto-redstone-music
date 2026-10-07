package com.autoredstonemusic.network;

import com.autoredstonemusic.AutoRedstoneMusicMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** C→S：上传分块（hash 定位会话，序号 + ≤28KB 字节）。 */
public record UploadChunkPayload(String hash, int index, byte[] data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<UploadChunkPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(AutoRedstoneMusicMod.MODID, "upload_chunk"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UploadChunkPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, UploadChunkPayload::hash,
            ByteBufCodecs.VAR_INT, UploadChunkPayload::index,
            ByteBufCodecs.BYTE_ARRAY, UploadChunkPayload::data,
            UploadChunkPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
