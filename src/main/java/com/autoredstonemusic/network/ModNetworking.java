package com.autoredstonemusic.network;

import com.autoredstonemusic.AutoRedstoneMusicMod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 网络层：仅两个 C→S 包（歌曲分块上传）。放置/启停/拆除都走物品与控制器的服务端交互，
 * 无需额外包；反馈用 displayClientMessage。
 */
public final class ModNetworking {
    private ModNetworking() {}

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(ModNetworking::onRegisterPayloads);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(UploadBeginPayload.TYPE, UploadBeginPayload.STREAM_CODEC, ServerUploadHandler::onBegin);
        registrar.playToServer(UploadChunkPayload.TYPE, UploadChunkPayload.STREAM_CODEC, ServerUploadHandler::onChunk);
        registrar.playToClient(SpeakerSyncPayload.TYPE, SpeakerSyncPayload.STREAM_CODEC, (payload, context) ->
                com.autoredstonemusic.data.ClientPitchStore.apply(payload.positions(), payload.data()));
        registrar.playToClient(OpenPlaybackUIPayload.TYPE, OpenPlaybackUIPayload.STREAM_CODEC, (payload, context) ->
                com.autoredstonemusic.client.ClientPlaybackHooks.openUI(payload.pos(), payload.songName(),
                        net.minecraft.core.Direction.values()[Math.floorMod(payload.facingIndex(), 6)],
                        payload.lineCount(), payload.totalTicks()));
        registrar.playToClient(RecordingBeginPayload.TYPE, RecordingBeginPayload.STREAM_CODEC, (payload, context) ->
                com.autoredstonemusic.client.ClientPlaybackHooks.beginRecording(payload.songName()));
        registrar.playToClient(RecordingStopPayload.TYPE, RecordingStopPayload.STREAM_CODEC, (payload, context) ->
                com.autoredstonemusic.client.ClientPlaybackHooks.stopRecording());
        registrar.playToServer(StartPlaybackPayload.TYPE, StartPlaybackPayload.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer sp
                    && sp.level() instanceof net.minecraft.server.level.ServerLevel slevel
                    && slevel.getBlockEntity(payload.pos())
                    instanceof com.autoredstonemusic.blockentity.MusicControllerBlockEntity be) {
                be.handleStartPlayback(sp, payload.mode(), payload.viewOffset(),
                        payload.fps(), payload.width(), payload.height());
            }
        });
    }
}
