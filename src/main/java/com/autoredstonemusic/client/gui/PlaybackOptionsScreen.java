package com.autoredstonemusic.client.gui;

import com.autoredstonemusic.Config;
import com.autoredstonemusic.client.ClientPlaybackHooks;
import com.autoredstonemusic.network.StartPlaybackPayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * 播放选项界面（右键控制器后打开）：
 * 经典模式（骑载具随波前）/ 观赏模式（落后 N 格、速度与波前一致）/
 * 录制模式（合并原"摄像机"与"实时录制"：世界实时运行 + 内录画面 + 内录游戏声音）。
 */
public class PlaybackOptionsScreen extends Screen {
    private static final int MODE_CLASSIC = 0;
    private static final int MODE_VIEWING = 1;
    private static final int MODE_RECORD = 2;

    private static final int[] FPS_CHOICES = {24, 30, 60, 120};

    private final BlockPos pos;
    private final String songName;
    private int mode = MODE_CLASSIC;
    private int viewOffset = 8;
    private int fps = 60;
    private int fpsIdx = 2;
    private final List<String> info = new ArrayList<>();
    private Button viewOffsetButton;
    private Button fpsButton;

    public PlaybackOptionsScreen(BlockPos pos, String songName) {
        super(Component.translatable("screen.auto_redstone_music.playback_title"));
        this.pos = pos;
        this.songName = songName;
    }

    @Override
    protected void init() {
        int y = 56;
        addRenderableWidget(Button.builder(Component.translatable("screen.auto_redstone_music.mode_classic"),
                b -> setMode(MODE_CLASSIC)).pos(30, y).size(130, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.auto_redstone_music.mode_viewing"),
                b -> setMode(MODE_VIEWING)).pos(166, y).size(130, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.auto_redstone_music.mode_record"),
                b -> setMode(MODE_RECORD)).pos(302, y).size(130, 20).build());
        y += 26;
        viewOffsetButton = Button.builder(viewOffsetText(), b -> {
            viewOffset = viewOffset >= 30 ? 0 : viewOffset + 2;
            viewOffsetButton.setMessage(viewOffsetText());
        }).pos(30, y).size(180, 20).build();
        addRenderableWidget(viewOffsetButton);
        y += 26;
        fpsButton = Button.builder(fpsText(), b -> {
            fpsIdx = (fpsIdx + 1) % FPS_CHOICES.length;
            fps = FPS_CHOICES[fpsIdx];
            fpsButton.setMessage(fpsText());
        }).pos(30, y).size(180, 20).build();
        addRenderableWidget(fpsButton);
        y += 34;
        addRenderableWidget(Button.builder(Component.translatable("screen.auto_redstone_music.start"),
                b -> start()).pos(30, y).size(180, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.auto_redstone_music.cancel"),
                b -> onClose()).pos(220, y).size(100, 20).build());
        refreshInfo();
    }

    private void setMode(int m) {
        this.mode = m;
        refreshInfo();
    }

    private Component viewOffsetText() {
        return Component.translatable("screen.auto_redstone_music.view_offset", viewOffset);
    }

    private Component fpsText() {
        return Component.translatable("screen.auto_redstone_music.fps", fps);
    }

    private void refreshInfo() {
        info.clear();
        info.add("§7" + songName);
        if (mode == MODE_RECORD) {
            info.add("§e" + Component.translatable("screen.auto_redstone_music.info_record_1").getString());
            info.add("§e" + Component.translatable("screen.auto_redstone_music.info_record_2").getString());
            info.add("§7" + Component.translatable("screen.auto_redstone_music.info_record_audio",
                    Config.AUDIO_CAPTURE_DEVICE.isEmpty()
                            ? Component.translatable("screen.auto_redstone_music.audio_auto")
                            : Component.literal(Config.AUDIO_CAPTURE_DEVICE)).getString());
        } else if (mode == MODE_VIEWING) {
            info.add("§7" + Component.translatable("screen.auto_redstone_music.info_viewing", viewOffset).getString());
        } else {
            info.add("§7" + Component.translatable("screen.auto_redstone_music.info_classic").getString());
        }
    }

    private void start() {
        ClientPlaybackHooks.pendingFps = fps;
        ClientPacketDistributor.sendToServer(new StartPlaybackPayload(
                pos, mode, viewOffset, fps, 0, 0));
        onClose();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xD80A0A12);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, 20, 0xFFE0C3FC);
        int y = 36;
        for (String line : info) {
            graphics.text(this.font, line, 30, y, 0xFFE8E2F4);
            y += 11;
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(null);
    }
}
