package com.autoredstonemusic.network;

import com.autoredstonemusic.AutoRedstoneMusicMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * S→C：右键控制器后打开播放选项界面（经典/观赏/摄像机/实时录制）。
 * 附带区域几何信息（facing/线数/尾刻）供客户端摄像机渲染与 UI 展示使用。
 */
public record OpenPlaybackUIPayload(BlockPos pos, String songName, byte facingIndex, int lineCount, int totalTicks)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OpenPlaybackUIPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(AutoRedstoneMusicMod.MODID, "open_playback_ui"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenPlaybackUIPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenPlaybackUIPayload::pos,
            ByteBufCodecs.STRING_UTF8, OpenPlaybackUIPayload::songName,
            ByteBufCodecs.BYTE, OpenPlaybackUIPayload::facingIndex,
            ByteBufCodecs.VAR_INT, OpenPlaybackUIPayload::lineCount,
            ByteBufCodecs.VAR_INT, OpenPlaybackUIPayload::totalTicks,
            OpenPlaybackUIPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
