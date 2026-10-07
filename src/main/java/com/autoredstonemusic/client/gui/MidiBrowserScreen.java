package com.autoredstonemusic.client.gui;

import com.autoredstonemusic.Config;
import com.autoredstonemusic.Log;
import com.autoredstonemusic.client.ClientCinemaCache;
import com.autoredstonemusic.arrange.Arranger;
import com.autoredstonemusic.arrange.InstrumentMap;
import com.autoredstonemusic.arrange.SongArrangement;
import com.autoredstonemusic.midi.MidiTimeline;
import com.autoredstonemusic.midi.TimelineBuilder;
import com.autoredstonemusic.midi.SmfParser;
import com.autoredstonemusic.network.UploadBeginPayload;
import com.autoredstonemusic.network.UploadChunkPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * MIDI 浏览器（纯客户端 Screen，仿 entity_modifier 的程序化绘制，无 gui 贴图）。
 * 左侧文件列表（gamedir/midi/*.mid），右侧解析报告，底部按钮：刷新/导入解析/应用/取消。
 * "应用" = 后台完成 编排 + 链编译自检 → 分块上传 → 关闭界面。
 */
public class MidiBrowserScreen extends Screen {
    private static final int LIST_W = 150;
    private static final int ROW_H = 16;
    private static final int MARGIN = 8;

    private final List<Path> files = new ArrayList<>();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicReference<ParsedResult> parsed = new AtomicReference<>();
    private CompletableFuture<?> runningTask;
    private int selected = -1;
    private float listScroll;
    private float reportScroll;
    private List<String> reportLines = List.of();
    private String statusLine = "";

    private Button refreshButton;
    private Button parseButton;
    private Button applyButton;
    private int contentTop;
    private int contentBottom;
    private int reportX;

    private record ParsedResult(String fileName, MidiTimeline timeline, List<String> report, String error) {}

    public MidiBrowserScreen() {
        super(Component.translatable("screen.auto_redstone_music.title"));
        Log.reinit(net.neoforged.fml.loading.FMLPaths.GAMEDIR.get());
    }

    private Button lampButton;
    private boolean lampsEnabled = false;

    private Component lampText() {
        return Component.translatable(lampsEnabled
                ? "screen.auto_redstone_music.lamps_on"
                : "screen.auto_redstone_music.lamps_off");
    }

    @Override
    protected void init() {
        int footerH = 24;
        contentTop = MARGIN + 16;
        contentBottom = this.height - footerH - MARGIN;
        reportX = MARGIN + LIST_W + 8;

        int buttonY = this.height - footerH - MARGIN + 4;
        int bw = 70;
        refreshButton = Button.builder(Component.translatable("screen.auto_redstone_music.refresh"), b -> refreshFiles())
                .pos(MARGIN, buttonY).size(bw, 18).build();
        parseButton = Button.builder(Component.translatable("screen.auto_redstone_music.parse"), b -> parseSelected())
                .pos(MARGIN + bw + 4, buttonY).size(bw, 18).build();
        applyButton = Button.builder(Component.translatable("screen.auto_redstone_music.apply"), b -> applySelected())
                .pos(this.width - bw * 2 - 8, buttonY).size(bw, 18).build();
        Button cancelButton = Button.builder(Component.translatable("screen.auto_redstone_music.cancel"), b -> onClose())
                .pos(this.width - bw - MARGIN, buttonY).size(bw, 18).build();
        lampButton = Button.builder(lampText(), b -> {
            lampsEnabled = !lampsEnabled;
            b.setMessage(lampText());
        }).pos(MARGIN + (bw + 4) * 2, buttonY).size(bw + 20, 18).build();
        this.addRenderableWidget(lampButton);
        this.addRenderableWidget(refreshButton);
        this.addRenderableWidget(parseButton);
        this.addRenderableWidget(applyButton);
        this.addRenderableWidget(cancelButton);
        refreshFiles();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xCC0A0A12);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int footerTop = contentBottom + 4;

        graphics.centeredText(this.font, this.title, this.width / 2, MARGIN, 0xFFE0C3FC);
        graphics.outline(MARGIN, contentTop, LIST_W, contentBottom - contentTop, 0xFF4A3B63);
        graphics.outline(reportX, contentTop, this.width - MARGIN - reportX, contentBottom - contentTop, 0xFF4A3B63);

        // 文件列表
        int visibleRows = (contentBottom - contentTop - 4) / ROW_H;
        int maxOffset = Math.max(0, files.size() - visibleRows);
        int startRow = Math.min((int) listScroll, maxOffset);
        for (int row = 0; row < visibleRows && startRow + row < files.size(); row++) {
            int idx = startRow + row;
            int y = contentTop + 2 + row * ROW_H;
            boolean hovered = mouseX >= MARGIN && mouseX < MARGIN + LIST_W && mouseY >= y && mouseY < y + ROW_H;
            if (idx == selected) {
                graphics.fill(MARGIN + 1, y, MARGIN + LIST_W - 1, y + ROW_H, 0x804080FF);
            } else if (hovered) {
                graphics.fill(MARGIN + 1, y, MARGIN + LIST_W - 1, y + ROW_H, 0x40FFFFFF);
            }
            String name = files.get(idx).getFileName().toString();
            graphics.text(this.font, ellipsize(name, LIST_W - 8), MARGIN + 4, y + 4,
                    idx == selected ? 0xFFFFFFFF : 0xFFB8B0C8);
        }
        if (files.isEmpty()) {
            graphics.text(this.font, Component.translatable("screen.auto_redstone_music.no_files").getString(),
                    MARGIN + 6, contentTop + 10, 0xFF888888);
        }

        // 报告区（滚动）
        List<String> lines = reportLines;
        int reportVisible = (contentBottom - contentTop - 8) / 10;
        int reportMaxOffset = Math.max(0, lines.size() - reportVisible);
        int reportStart = Math.min((int) reportScroll, reportMaxOffset);
        for (int i = 0; i < reportVisible && reportStart + i < lines.size(); i++) {
            graphics.text(this.font, lines.get(reportStart + i), reportX + 6, contentTop + 6 + i * 10, 0xFFE8E2F4);
        }

        graphics.text(this.font, statusLine, MARGIN, footerTop - 12, 0xFF9C8FC0);
    }

    private String ellipsize(String text, int maxWidth) {
        return this.font.plainSubstrByWidth(text, maxWidth, false);
    }

    // ---- 数据 ----

    private void refreshFiles() {
        parsed.set(null);
        reportLines = List.of();
        selected = -1;
        files.clear();
        Path dir = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve(Config.MIDI_DIR);
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return (n.endsWith(".mid") || n.endsWith(".midi")) && !p.getFileName().toString().startsWith(".");
            }).sorted().forEach(files::add);
            statusLine = Component.translatable("screen.auto_redstone_music.found", files.size()).getString();
        } catch (IOException e) {
            statusLine = Component.translatable("screen.auto_redstone_music.dir_error").getString();
        }
    }

    private void parseSelected() {
        if (selected < 0 || selected >= files.size()) {
            statusLine = Component.translatable("screen.auto_redstone_music.select_first").getString();
            return;
        }
        if (busy.get()) {
            return;
        }
        Path file = files.get(selected);
        String name = file.getFileName().toString();
        statusLine = Component.translatable("screen.auto_redstone_music.parsing").getString();
        busy.set(true);
        runningTask = CompletableFuture.supplyAsync(() -> {
            try {
                byte[] bytes = Files.readAllBytes(file);
                SmfParser.ParsedSmf smf = SmfParser.parse(bytes);
                MidiTimeline timeline = TimelineBuilder.build(smf);
                Log.info("解析完成: {} -> 音符 {}, 时长 {}, 轨道 {} | 事件: NoteOn {} NoteOff {} 踏板挂起 {} 同键截断 {} 弯音 {} 变音色 {} 控制器 {} 变速 {}",
                        name, timeline.notes.size(), timeline.durationText(), timeline.trackSummaries.size(),
                        timeline.noteOnCount, timeline.noteOffCount, timeline.sustainParks, timeline.overlapTruncations,
                        timeline.bendEventCount, timeline.programChangeCount, timeline.controlChangeCount, timeline.tempoChangeCount);
                return new ParsedResult(name, timeline, buildReport(name, timeline), null);
            } catch (Exception e) {
                Log.error("解析失败: " + name, e);
                return new ParsedResult(name, null, List.of(), e.getMessage() == null ? e.toString() : e.getMessage());
            }
        });
    }

    private void applySelected() {
        if (selected < 0 || selected >= files.size()) {
            statusLine = Component.translatable("screen.auto_redstone_music.select_first").getString();
            return;
        }
        if (busy.get()) {
            return;
        }
        Path file = files.get(selected);
        String name = file.getFileName().toString();
        statusLine = Component.translatable("screen.auto_redstone_music.applying").getString();
        busy.set(true);
        runningTask = CompletableFuture.supplyAsync(() -> {
            try {
                byte[] midiBytes = Files.readAllBytes(file);
                SongArrangement arrangement = Arranger.arrangeFile(midiBytes, name);
                ClientCinemaCache.songName = name;
                ClientCinemaCache.totalTicks = arrangement.totalTicks;
                byte[] payload = arrangement.toBytes(); // 上传编排产物（ARM 格式），不是 MIDI 原始字节
                Log.info("编排完成: {} -> {}", name, Log.bytesDiag(payload));
                return new Object[]{payload, arrangement, null};
            } catch (Exception e) {
                Log.error("编排失败: " + name, e);
                return new Object[]{null, null, e.getMessage() == null ? e.toString() : e.getMessage()};
            }
        }).whenComplete((result, throwable) -> net.minecraft.client.Minecraft.getInstance().execute(() -> {
            busy.set(false);
            if (throwable != null || result[2] != null) {
                String error = throwable != null ? throwable.toString() : (String) result[2];
                reportLines = List.of("§c" + Component.translatable("screen.auto_redstone_music.failed").getString(), error);
                statusLine = Component.translatable("screen.auto_redstone_music.failed").getString();
                return;
            }
            byte[] payload = (byte[]) result[0];
            SongArrangement arrangement = (SongArrangement) result[1];
            upload(payload, name);
            int blocks = estimateBlocks(arrangement);
            statusLine = Component.translatable("screen.auto_redstone_music.applied_status",
                    arrangement.lines.size(), blocks).getString();
            onClose();
        }));
    }

    private int estimateBlocks(SongArrangement arrangement) {
        return arrangement.lines.size() * (arrangement.totalTicks * 2 + 16) * 4 + arrangement.totalNotes * 5;
    }

    private List<String> buildReport(String fileName, MidiTimeline timeline) {
        List<String> lines = new ArrayList<>();
        lines.add("§d" + fileName);
        lines.add("§7时长: §f" + timeline.durationText()
                + "  §7格式: §ftype " + timeline.format
                + (timeline.smpte ? " SMPTE" : "  §7PPQ: §f" + timeline.ppq));
        lines.add("§7音符: §f" + timeline.notes.size()
                + "  §7轨: §f" + timeline.trackSummaries.size()
                + "  §7延音踏板段: §f" + timeline.sustainPedalSections);
        lines.add("§7事件: NoteOn §f" + timeline.noteOnCount
                + " §7/ NoteOff §f" + timeline.noteOffCount
                + " §7/ 变音色 §f" + timeline.programChangeCount
                + " §7/ 控制器 §f" + timeline.controlChangeCount
                + " §7/ 变速 §f" + timeline.tempoChangeCount);
        lines.add("§7延音挂起 §f" + timeline.sustainParks
                + " §7/ 同键截断 §f" + timeline.overlapTruncations
                + " §7/ 弯音(无法表达) §f" + timeline.bendEventCount);
        if (!timeline.smpte && timeline.tempoChanges.size() > 1) {
            StringBuilder bpm = new StringBuilder("§7BPM 曲线: §f");
            int shown = 0;
            for (MidiTimeline.TempoChange tc : timeline.tempoChanges) {
                if (tc.usPerQuarter() <= 0) {
                    continue;
                }
                long bpmVal = Math.round(60_000_000.0 / tc.usPerQuarter());
                if (shown == 0) {
                    bpm.append(bpmVal);
                } else if (shown < 4) {
                    double sec = tc.startSec();
                    bpm.append(" → ").append(bpmVal).append("@").append(String.format("%d:%02d", (int) sec / 60, (int) sec % 60));
                }
                shown++;
            }
            if (shown > 4) {
                bpm.append(" …（共 ").append(shown).append(" 段）");
            }
            lines.add(bpm.toString());
        }
        lines.add("§7音符时长: 最短 §f" + String.format("%.2fs", timeline.minNoteSec)
                + " §7最长 §f" + String.format("%.2fs", timeline.maxNoteSec)
                + " §7平均 §f" + String.format("%.2fs", timeline.avgNoteSec)
                + " §8(音符盒为固定衰减音色，时长不影响发声)");
        lines.add("");
        lines.add("§d--- 音轨 ---");
        for (MidiTimeline.TrackSummary track : timeline.trackSummaries) {
            if (track.noteCount() == 0) {
                continue;
            }
            StringBuilder programs = new StringBuilder();
            for (int i = 0; i < track.programs().size() && i < 3; i++) {
                if (i > 0) {
                    programs.append('/');
                }
                int p = track.programs().get(i);
                programs.append(p == 9 ? "drums" : InstrumentMap.gmName(p).substring(3));
            }
            lines.add("§7· " + track.name()
                    + " §8| §f" + track.noteCount() + " 音符"
                    + " §8| §f" + programs
                    + " §8| §f" + track.minKey() + "-" + track.maxKey());
        }
        if (!timeline.warnings.isEmpty()) {
            lines.add("");
            lines.add("§e--- 提示 ---");
            for (String warning : timeline.warnings) {
                lines.add("§e· " + warning);
            }
        }
        return lines;
    }

    private void upload(byte[] bytes, String songName) {
        String hash = sha1(bytes);
        int chunks = (bytes.length + Config.UPLOAD_CHUNK_SIZE - 1) / Config.UPLOAD_CHUNK_SIZE;
        Log.info("开始上传: {} 共 {} 字节 / {} 块, sha1={}", songName, bytes.length, chunks, hash);
        ClientPacketDistributor.sendToServer(new UploadBeginPayload(songName, hash, bytes.length, chunks, lampsEnabled));
        for (int i = 0; i < chunks; i++) {
            int from = i * Config.UPLOAD_CHUNK_SIZE;
            int to = Math.min(bytes.length, from + Config.UPLOAD_CHUNK_SIZE);
            byte[] part = new byte[to - from];
            System.arraycopy(bytes, from, part, 0, part.length);
            Log.debug("  发送块 {}/{} ({} 字节)", i + 1, chunks, part.length);
            ClientPacketDistributor.sendToServer(new UploadChunkPayload(hash, i, part));
        }
    }

    private String sha1(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append("%02x".formatted(b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- 每刻轮询后台任务 ----

    @Override
    public void tick() {
        super.tick();
        if (runningTask != null && runningTask.isDone() && busy.get()) {
            if (runningTask instanceof CompletableFuture<?> cf && cf.join() instanceof ParsedResult result) {
                parsed.set(result);
                reportLines = result.error() != null
                        ? List.of("§c解析失败：", result.error())
                        : result.report();
                statusLine = result.error() != null ? result.error()
                        : Component.translatable("screen.auto_redstone_music.parsed").getString();
            }
            runningTask = null;
            busy.set(false);
        }
        parseButton.active = !busy.get();
        applyButton.active = !busy.get();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= MARGIN && mouseX < MARGIN + LIST_W && mouseY >= contentTop && mouseY < contentBottom) {
            listScroll = Math.max(0, listScroll - (float) scrollY * 2);
            return true;
        }
        if (mouseX >= reportX && mouseY >= contentTop && mouseY < contentBottom) {
            reportScroll = Math.max(0, reportScroll - (float) scrollY * 2);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean isDoubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        if (mouseX >= MARGIN && mouseX < MARGIN + LIST_W && mouseY >= contentTop && mouseY < contentBottom) {
            int visibleRows = (contentBottom - contentTop - 4) / ROW_H;
            int maxOffset = Math.max(0, files.size() - visibleRows);
            int startRow = Math.min((int) listScroll, maxOffset);
            int row = (int) ((mouseY - contentTop - 2) / ROW_H);
            int idx = startRow + row;
            if (row >= 0 && idx < files.size()) {
                selected = idx;
                return true;
            }
        }
        return super.mouseClicked(event, isDoubleClick);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(null);
    }
}
