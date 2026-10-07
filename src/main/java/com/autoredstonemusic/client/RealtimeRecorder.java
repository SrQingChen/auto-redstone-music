package com.autoredstonemusic.client;

import com.autoredstonemusic.Config;
import com.autoredstonemusic.Log;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.io.BufferedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 录制模式（合并原"摄像机"与"实时录制"）：世界实时运行，玩家骑载具以第一人称沿波前滑行
 * （默认朝向 = 行进方向）。**内录视频**（抓取主帧缓冲，当前窗口分辨率，稳定优先）+
 * **内录游戏声音**（ffmpeg dshow 系统回环设备，自动检测"立体声混音"；捕获的即游戏实际输出，
 * 含模组环境音）。帧率按墙钟做帧复制/丢弃保证音画同步。
 *
 * <p>像素格式（26.1.2 实证）：{@code NativeImage.getPixels()} 返回标准 ARGB（0xAARRGGBB），
 * 且 {@code Screenshot.takeScreenshot} 已完成 Y 翻转——转 RGBA 字节序时 R/B 必须按位取对
 * （此前 R/B 写反导致画面颜色错乱）。
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = "auto_redstone_music")
public final class RealtimeRecorder {
    private RealtimeRecorder() {}

    private static boolean recording;
    private static long startMs;
    private static int recordMs;
    private static int fps;
    private static int captureW;
    private static int captureH;
    private static long framesWritten;
    private static boolean sizeWarned;

    private static BufferedOutputStream videoIn;
    private static Process videoProc;
    private static Process audioProc;
    private static Path videoPath;
    private static Path audioPath;
    private static String activeAudioDevice = "";

    /** 开始录制。fpsTarget = 目标帧率（24/30/60/120）。 */
    public static boolean begin(String song, int durationTicks, int fpsTarget) {
        Minecraft mc = Minecraft.getInstance();
        if (recording) {
            Log.warn("已在录制中，忽略重复开始");
            return false;
        }
        try {
            Path outDir = com.autoredstonemusic.ConfigHelper.videoDir();
            Files.createDirectories(outDir);
            String safe = song.replaceAll("[\\\\/:*?\"<>|]", "_");
            videoPath = outDir.resolve(safe + ".mp4");
            audioPath = outDir.resolve(safe + "_audio.wav");
            recordMs = (int) (durationTicks * 50L) + 3000;

            fps = Math.max(24, fpsTarget);
            framesWritten = 0;
            sizeWarned = false;

            // 内录：当前窗口的实际渲染分辨率
            captureW = mc.getMainRenderTarget().width;
            captureH = mc.getMainRenderTarget().height;

            List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-y", "-loglevel", "error",
                    "-f", "rawvideo", "-pix_fmt", "rgba",
                    "-s", captureW + "x" + captureH,
                    "-r", String.valueOf(fps),
                    "-i", "-",
                    "-c:v", "libx264", "-preset", "fast", "-crf", "17",
                    "-pix_fmt", "yuv420p", videoPath.toString()));
            videoProc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            videoIn = new BufferedOutputStream(videoProc.getOutputStream(), 1 << 20);

            // 内录游戏声音：dshow 系统回环（自动检测或配置指定）
            activeAudioDevice = detectAudioDevice();
            if (!activeAudioDevice.isEmpty()) {
                audioProc = new ProcessBuilder("ffmpeg", "-y", "-loglevel", "error",
                        "-f", "dshow", "-i", "audio=" + activeAudioDevice,
                        "-t", String.valueOf(recordMs / 1000 + 5),
                        audioPath.toString()).redirectErrorStream(true).start();
                Log.info("游戏声音内录已启动（dshow 系统回环）: {}", activeAudioDevice);
            } else {
                Log.warn("未找到系统回环录音设备（立体声混音），本次仅录画面。"
                        + "可在系统声音设置中启用“立体声混音”，或在 Config.AUDIO_CAPTURE_DEVICE 手动指定设备名");
            }

            recording = true;
            startMs = System.currentTimeMillis();
            Log.info("录制开始: {}（{}ms @ {}fps 目标，内录 {}x{}）", song, recordMs, fps, captureW, captureH);
            if (mc.player != null) {
                mc.player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.auto_redstone_music.recording_start"));
            }
            return true;
        } catch (Exception e) {
            Log.error("录制启动失败（需要 ffmpeg）", e);
            cleanup();
            return false;
        }
    }

    /** 系统回环录音设备自动检测：优先配置值，否则扫描 dshow 设备列表找"立体声混音"等回环设备。 */
    private static String detectAudioDevice() {
        if (!Config.AUDIO_CAPTURE_DEVICE.isEmpty()) {
            return Config.AUDIO_CAPTURE_DEVICE;
        }
        try {
            Process p = new ProcessBuilder("ffmpeg", "-list_devices", "true", "-f", "dshow", "-i", "dummy")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(10, TimeUnit.SECONDS);
            String[] lines = out.split("\r?\n");
            boolean inAudio = false;
            for (String line : lines) {
                if (line.contains("DirectShow audio devices")) {
                    inAudio = true;
                    continue;
                }
                if (line.contains("DirectShow video devices")) {
                    inAudio = false;
                }
                if (!inAudio) {
                    continue;
                }
                int q1 = line.indexOf('"');
                int q2 = line.lastIndexOf('"');
                if (q1 >= 0 && q2 > q1) {
                    String name = line.substring(q1 + 1, q2);
                    String lower = name.toLowerCase();
                    if (name.contains("混音") || name.contains("立体声")
                            || lower.contains("stereo mix") || lower.contains("loopback")
                            || lower.contains("what u hear") || name.contains("CABLE Output")) {
                        Log.info("自动检测到回环录音设备: {}", name);
                        return name;
                    }
                }
            }
        } catch (Exception e) {
            Log.warn("音频设备自动检测失败: " + e);
        }
        return "";
    }

    /** 每渲染一帧：墙钟对齐的帧复制/丢弃 + 抓帧（AfterLevel：世界已渲染、HUD 之前，画面干净）。 */
    @SubscribeEvent
    public static void onAfterLevel(RenderLevelStageEvent.AfterLevel event) {
        if (!recording || videoIn == null) {
            return;
        }
        try {
            var rt = Minecraft.getInstance().getMainRenderTarget();
            if (rt.width != captureW || rt.height != captureH) {
                if (!sizeWarned) {
                    sizeWarned = true;
                    Log.warn("窗口尺寸已变化（{}x{} != 录制 {}x{}），录制期间请勿调整窗口",
                            rt.width, rt.height, captureW, captureH);
                }
                return;
            }
            long elapsed = System.currentTimeMillis() - startMs;
            long due = elapsed * fps / 1000 - framesWritten;
            if (due <= 0) {
                return; // 渲染快于目标帧率：丢帧
            }
            int copies = (int) Math.min(due, 4); // 渲染慢于目标帧率：复制补偿（上限 4）
            net.minecraft.client.Screenshot.takeScreenshot(rt, img -> {
                try {
                    // getPixels() = 标准 ARGB（0xAARRGGBB），takeScreenshot 已做 Y 翻转
                    int[] px = img.getPixels();
                    byte[] rgba = new byte[px.length * 4];
                    for (int i = 0; i < px.length; i++) {
                        int p = px[i];
                        rgba[i * 4] = (byte) (p >> 16);      // R
                        rgba[i * 4 + 1] = (byte) (p >> 8);   // G
                        rgba[i * 4 + 2] = (byte) p;          // B
                        rgba[i * 4 + 3] = (byte) (p >>> 24); // A
                    }
                    for (int c = 0; c < copies; c++) {
                        videoIn.write(rgba);
                        framesWritten++;
                    }
                } catch (Exception e) {
                    Log.error("录制写帧失败", e);
                }
            });
        } catch (Exception e) {
            Log.error("录制抓帧异常", e);
            cleanup();
        }
    }

    /** 每客户端 tick：时长兜底检查（正常由服务端 stop 包触发结束）。 */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (recording && System.currentTimeMillis() - startMs >= recordMs) {
            finish();
        }
    }

    /** 结束录制并合成（视频 + 内录音频 → mp4）。 */
    public static void finish() {
        if (!recording) {
            return;
        }
        recording = false;
        Minecraft mc = Minecraft.getInstance();
        try {
            // 音频：优先优雅收尾（写入 q 让 ffmpeg 写完文件头）
            if (audioProc != null && audioProc.isAlive()) {
                try {
                    audioProc.getOutputStream().write('q');
                    audioProc.getOutputStream().flush();
                    audioProc.getOutputStream().close();
                } catch (Exception ignored) {
                }
                if (!audioProc.waitFor(5, TimeUnit.SECONDS)) {
                    audioProc.destroyForcibly();
                }
            }
            // 视频：关闭管道 → ffmpeg 收尾
            videoIn.close();
            videoProc.waitFor();

            Log.info("录制结束: {} 帧（目标 {}fps）", framesWritten, fps);
            boolean hasAudio = audioProc != null && Files.exists(audioPath) && Files.size(audioPath) > 4096;
            if (hasAudio) {
                Path muxed = videoPath.resolveSibling(
                        videoPath.getFileName().toString().replace(".mp4", "_final.mp4"));
                new ProcessBuilder("ffmpeg", "-y", "-loglevel", "error",
                        "-i", videoPath.toString(), "-i", audioPath.toString(),
                        "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-shortest",
                        muxed.toString()).inheritIO().start().waitFor();
                Files.deleteIfExists(audioPath);
                Files.move(muxed, videoPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else if (audioProc != null) {
                Log.warn("音频文件缺失或不完整，仅保留视频");
                Files.deleteIfExists(audioPath);
            }
            Log.info("录制视频输出: {}", videoPath);
            if (mc.player != null) {
                mc.player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.auto_redstone_music.cinema_done"));
            }
        } catch (Exception e) {
            Log.error("录制收尾失败", e);
        } finally {
            videoIn = null;
            videoProc = null;
            audioProc = null;
        }
    }

    private static void cleanup() {
        recording = false;
        try {
            if (videoIn != null) {
                videoIn.close();
            }
            if (videoProc != null && videoProc.isAlive()) {
                videoProc.destroyForcibly();
            }
            if (audioProc != null && audioProc.isAlive()) {
                audioProc.destroyForcibly();
            }
        } catch (Exception ignored) {
        }
        videoIn = null;
        videoProc = null;
        audioProc = null;
    }
}
