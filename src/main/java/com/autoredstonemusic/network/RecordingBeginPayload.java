package com.autoredstonemusic.network;

import com.autoredstonemusic.AutoRedstoneMusicMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** S→C：录制模式已启动（世界实时运行），客户端开始内录画面与游戏声音。 */
public record RecordingBeginPayload(String songName) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RecordingBeginPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(AutoRedstoneMusicMod.MODID, "recording_begin"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RecordingBeginPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, RecordingBeginPayload::songName,
            RecordingBeginPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
