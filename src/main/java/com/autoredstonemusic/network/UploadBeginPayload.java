package com.autoredstonemusic.network;

import com.autoredstonemusic.AutoRedstoneMusicMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C→S：上传开始（歌名、SHA-1、总字节、分块数）。服务端创建会话，等待 {@link UploadChunkPayload}。
 */
public record UploadBeginPayload(String songName, String hash, int totalBytes, int chunkCount, boolean lamps)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<UploadBeginPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(AutoRedstoneMusicMod.MODID, "upload_begin"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UploadBeginPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, UploadBeginPayload::songName,
            ByteBufCodecs.STRING_UTF8, UploadBeginPayload::hash,
            ByteBufCodecs.VAR_INT, UploadBeginPayload::totalBytes,
            ByteBufCodecs.VAR_INT, UploadBeginPayload::chunkCount,
            ByteBufCodecs.BOOL, UploadBeginPayload::lamps,
            UploadBeginPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
