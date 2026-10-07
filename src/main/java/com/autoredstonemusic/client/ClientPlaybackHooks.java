package com.autoredstonemusic.client;

import com.autoredstonemusic.Config;
import com.autoredstonemusic.client.gui.PlaybackOptionsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** 客户端桥接（仅供网络层调用；客户端专属类由分支懒加载保护）。 */
public final class ClientPlaybackHooks {
    private ClientPlaybackHooks() {}

    /** UI 提交的录制参数（网络包发出前写入）。 */
    public static int pendingFps = 60;

    public static void openUI(BlockPos pos, String songName, Direction facing, int lineCount, int totalTicks) {
        ClientCinemaCache.songName = songName;
        if (totalTicks > 0) {
            ClientCinemaCache.totalTicks = totalTicks;
        }
        Minecraft.getInstance().setScreen(new PlaybackOptionsScreen(pos, songName));
    }

    /** 服务端已启动录制模式（世界实时运行），客户端开始内录。 */
    public static void beginRecording(String songName) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> RealtimeRecorder.begin(songName,
                ClientCinemaCache.totalTicks + Config.TAIL_TICKS, pendingFps));
    }

    /** 服务端播放停止（自然结束/手动），客户端立即结束录制并保存。 */
    public static void stopRecording() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(RealtimeRecorder::finish);
    }
}
