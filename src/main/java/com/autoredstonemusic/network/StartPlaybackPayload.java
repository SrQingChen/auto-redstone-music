package com.autoredstonemusic.network;

import com.autoredstonemusic.AutoRedstoneMusicMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C→S：从播放选项界面发起播放。
 * mode：0=经典（骑载具随波前） 1=观赏（载具落后 viewOffset 格，速度与波前一致）
 * 2=摄像机（离线渲染：冻结世界、逐帧渲染、ffmpeg 输出视频；仅单人可用）。
 * cinematic 仅 mode=2 有效：fps 与 width/height 为输出视频参数。
 */
public record StartPlaybackPayload(BlockPos pos, int mode, int viewOffset,
                                   int fps, int width, int height) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<StartPlaybackPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(AutoRedstoneMusicMod.MODID, "start_playback"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StartPlaybackPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, StartPlaybackPayload::pos,
            ByteBufCodecs.VAR_INT, StartPlaybackPayload::mode,
            ByteBufCodecs.VAR_INT, StartPlaybackPayload::viewOffset,
            ByteBufCodecs.VAR_INT, StartPlaybackPayload::fps,
            ByteBufCodecs.VAR_INT, StartPlaybackPayload::width,
            ByteBufCodecs.VAR_INT, StartPlaybackPayload::height,
            StartPlaybackPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
