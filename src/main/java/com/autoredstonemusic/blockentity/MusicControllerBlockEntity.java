package com.autoredstonemusic.blockentity;

import com.autoredstonemusic.Config;
import com.autoredstonemusic.block.PrecisionRepeaterBlock;
import com.autoredstonemusic.registry.ModSounds;
import com.autoredstonemusic.Log;
import com.autoredstonemusic.ModRegistries;
import com.autoredstonemusic.arrange.ChainCompiler;
import com.autoredstonemusic.arrange.SongArrangement;
import com.autoredstonemusic.data.PitchChannel;
import com.autoredstonemusic.data.PitchChannelSync;
import com.autoredstonemusic.data.SongLibrary;
import com.autoredstonemusic.world.RegionGeometry;
import com.autoredstonemusic.world.RegionPlanner;
import com.mojang.serialization.Codec;
import org.jspecify.annotations.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 音乐控制器 BlockEntity：区域的元数据与状态机。
 *
 * <p>状态：BUILDING（分批铺设）→ IDLE → RUNNING（红石自行运转，BE 只做玩家搬运与收尾）→ IDLE；
 * REMOVING（确定性清拆后自毁）。播放引擎见 {@link ChainCompiler}。
 *
 * <p><b>与红石时钟同步</b>：startTick 记录启动刻，波前在第 slot 槽的时刻是
 * startTick + 2·slot 游戏刻（中继器延迟与世界 tick 同源），搬运玩家按同一公式推进，
 * 因此玩家与波前严格并行。
 */
public class MusicControllerBlockEntity extends BlockEntity {
    public enum State { IDLE, BUILDING, RUNNING, REMOVING }

    private static final String CARRY_TAG = "auto_redstone_music_carry";

    private String songHash = "";
    private String songName = "";
    private Direction facing = Direction.NORTH;
    private int lineCount = 1;
    /** 铺设：因目标区块未加载而暂存的写入（下刻重试）。 */
    private RegionPlanner.Write pendingWrite;
    /** 铺设：已 setChunkForced(true) 的区块（滑动窗口，边铺边放）。 */
    private final java.util.LinkedHashSet<Long> forcedChunks = new java.util.LinkedHashSet<>();

    /** 观赏模式：载具落后波前的格数。 */
    private int viewOffsetBlocks = 0;
    private int lastNoteTick = 0;
    private State state = State.IDLE;
    private UUID placer;
    /** 正在录制的玩家（瞬态，不持久化）。 */
    private UUID recorder;
    private final Set<UUID> followers = new HashSet<>();
    private long startTick;
    /** 播放结束后的视觉清扫时刻（-1 = 无）。 */
    private long sweepAt = -1;
    private int sweepCursorLine;
    private int sweepCursorCell;
    private int lastDoneWrites = -1;
    private long lastProgressGameTime;
    private boolean firstBuildJump = true;
    /** 连续跟随载具（隐形标记盔甲架）：玩家骑乘，服务端每刻驱动，随波前平滑滑行。 */
    private final Map<UUID, ArmorStand> carriers = new java.util.HashMap<>();
    /** 玩家跟随插值缓存：红线槽位 → 世界轴向偏移。 */
    private int[] arrivalTickCache = new int[0];
    private double[] arrivalZCache = new double[0];

    // 瞬态（可由 NBT + 歌曲库重建）
    private RegionGeometry geometry;
    private SongArrangement arrangement;
    private ChainCompiler.CompiledSong compiled;
    private RegionPlanner planner;
    private long lastReportTick;
    /** 引擎 B：各线当前波红石块位置（-1 无）。 */
    private int[] wavefrontZ = new int[0];

    public MusicControllerBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistries.MUSIC_CONTROLLER_BE.get(), pos, state);
    }

    // ---- 放置 ----

    /** 由生成器物品调用：解码编配、编译红石链、放置控制器并进入 BUILDING。 */
    public static void placeRegion(ServerLevel level, ServerPlayer player, BlockPos origin, String songName, byte[] songBytes, boolean lamps) {
        Log.info("放置请求: 玩家={} 锚点={} 收到数据 {}", player.getName().getString(), origin.toShortString(), Log.bytesDiag(songBytes));
        SongArrangement arrangement;
        try {
            arrangement = SongArrangement.fromBytes(songBytes);
        } catch (java.io.IOException e) {
            Log.error("编排解码失败! 收到数据 " + Log.bytesDiag(songBytes), e);
            player.sendSystemMessage(Component.literal("编排数据损坏: " + e.getMessage()).withStyle(ChatFormatting.RED));
            return;
        }
        if (arrangement.lines.size() > com.autoredstonemusic.world.RegionGeometry.MAX_SUPPORTED_LINES) {
            Log.error("分轨数 {} 超过布局容量 {}（编排器应已截断）",
                    arrangement.lines.size(), com.autoredstonemusic.world.RegionGeometry.MAX_SUPPORTED_LINES);
        }
        Log.info("编排解码成功: {} 线 {} 总刻 {} 音符 {}", songName, arrangement.lines.size(), arrangement.totalTicks, arrangement.totalNotes);
        ChainCompiler.CompiledSong compiled = ChainCompiler.compile(arrangement);
        Direction facing = player.getDirection();

        String hash = sha1(songBytes);
        SongLibrary.get(level.getServer()).put(hash, songName, songBytes);
        Log.debug("歌曲已入库: hash={} 名称={}", hash, songName);

        level.setBlock(origin, ModRegistries.MUSIC_CONTROLLER.get().defaultBlockState(), 3);
        if (!level.getBlockState(origin).is(ModRegistries.MUSIC_CONTROLLER.get())) {
            Log.error("控制器放置失败! setBlock 后读回的不是控制器方块: {}", level.getBlockState(origin));
            player.sendSystemMessage(Component.literal("控制器放置失败，请查看日志").withStyle(ChatFormatting.RED));
            return;
        }
        if (!(level.getBlockEntity(origin) instanceof MusicControllerBlockEntity be)) {
            Log.error("控制器放置失败! setBlock 后 BlockEntity 缺失或不匹配（位置 {}）", origin);
            player.sendSystemMessage(Component.literal("控制器放置失败，请查看日志").withStyle(ChatFormatting.RED));
            return;
        }
        Log.debug("控制器方块与 BlockEntity 均已就位: {}", origin);
        {
            be.songHash = hash;
            be.songName = songName;
            be.facing = facing;
            be.lineCount = arrangement.lines.size();
            be.lastNoteTick = maxNoteTick(arrangement);
            be.geometry = new RegionGeometry(origin, facing, arrangement.lines.size());
            be.arrangement = arrangement;
            be.compiled = compiled;
            be.placer = player.getUUID();
            be.planner = RegionPlanner.build(be.geometry, arrangement, compiled, lamps);
            be.state = State.BUILDING;
            be.setChanged();
            // 音高通道：铺设过的音符盒由 Mixin 播放 Salamander 采样（真实音高+力度）
            java.util.Map<Long, Long> channel = buildChannelEntries(arrangement, compiled, be.geometry);
            PitchChannel.putAll(level, channel);
            PitchChannelSync.sendToTracking(level, origin, channel, false);
            Log.info("音高通道登记 {} 个音符（全乐器采样）", channel.size());
            Log.info("开始铺设: {} 面向 {} 线 {} 红石灯={} 计划写入 {} 个方块",
                    songName, facing, arrangement.lines.size(), lamps, be.planner.totalWrites());
            player.sendOverlayMessage(Component.translatable("message.auto_redstone_music.building",
                    be.planner.totalWrites()).withStyle(ChatFormatting.GREEN));
        }
    }

    private static int maxNoteTick(SongArrangement arrangement) {
        int max = 0;
        for (SongArrangement.ArrangedLine line : arrangement.lines) {
            for (SongArrangement.ArrangedNote note : line.notes) {
                max = Math.max(max, note.tick());
            }
        }
        return max;
    }

    /** 由编译结果 + 几何计算全部登记音符（坐标 → 采样/键位/力度/折叠校验音）。 */
    private static java.util.Map<Long, Long> buildChannelEntries(SongArrangement arrangement,
                                                                 ChainCompiler.CompiledSong compiled,
                                                                 RegionGeometry geometry) {
        java.util.Map<Long, Long> entries = new java.util.HashMap<>();
        for (int line = 0; line < compiled.lineCells.size(); line++) {
            for (ChainCompiler.ChainCell cell : compiled.lineCells.get(line)) {
                if (cell.kind != ChainCompiler.Kind.DUST) {
                    continue;
                }
                for (ChainCompiler.SideNote side : cell.sideNotes) {
                    SongArrangement.ArrangedNote note = side.note();
                    BlockPos pos = geometry.cell(line, cell.z, side.side() * geometry.noteSideSign(line) * 2, 0);
                    int familyId = com.autoredstonemusic.registry.SampleTable.familyId(note.family());
                    if (familyId < 0) {
                        familyId = com.autoredstonemusic.registry.SampleTable.familyId("piano");
                    }
                    entries.put(pos.asLong(), PitchChannel.pack(
                            familyId, note.gainIdx(), note.midiKey(), note.velocity(), note.pitch()));
                }
            }
        }
        return entries;
    }

    public static String sha1(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append("%02x".formatted(b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- 交互 ----

    /** 触发目标区块的异步加载（滑动窗口：超过 48 个时释放最早的 32 个）。 */
    private void forceChunkAt(ServerLevel level, BlockPos pos) {
        long key = chunkKey(pos.getX() >> 4, pos.getZ() >> 4);
        if (forcedChunks.add(key)) {
            level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
            if (forcedChunks.size() > 48) {
                java.util.Iterator<Long> it = forcedChunks.iterator();
                int release = forcedChunks.size() - 32;
                for (int i = 0; i < release && it.hasNext(); i++) {
                    long oldKey = it.next();
                    level.setChunkForced(chunkX(oldKey), chunkZ(oldKey), false);
                    it.remove();
                }
            }
        }
    }

    private void releaseForcedChunks(ServerLevel level) {
        for (long key : forcedChunks) {
            level.setChunkForced(chunkX(key), chunkZ(key), false);
        }
        forcedChunks.clear();
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx & 0xFFFFFFFFL) | (((long) cz) << 32);
    }

    private static int chunkX(long key) {
        return (int) key;
    }

    private static int chunkZ(long key) {
        return (int) (key >> 32);
    }

    /** 构建异常/停滞时的安全回退：不再持续写入，回到 IDLE 并通知放置者。 */
    private void abortBuild(ServerLevel level, String reason) {
        planner = null;
        pendingWrite = null;
        releaseForcedChunks(level);
        state = State.IDLE;
        Log.warn("铺设已中止并回退到 IDLE（原因: {}）", reason);
        if (placer != null) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(placer);
            if (player != null) {
                player.sendSystemMessage(Component.literal("铺设已中止，详见日志（原因: " + reason + "）").withStyle(ChatFormatting.RED));
            }
        }
    }

    public void onUse(ServerPlayer player, boolean sneaking) {
        switch (state) {
            case IDLE -> {
                if (sneaking) {
                    startRemoval(player);
                } else {
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                            new com.autoredstonemusic.network.OpenPlaybackUIPayload(worldPosition, songName,
                                    (byte) facing.ordinal(), lineCount, lastNoteTick));
                }
            }
            case RUNNING -> stopPlayback(player, false);
            case BUILDING -> player.sendOverlayMessage(Component.translatable(
                    "message.auto_redstone_music.build_progress", songName, progressPercent()).withStyle(ChatFormatting.AQUA));
            case REMOVING -> player.sendOverlayMessage(Component.translatable(
                    "message.auto_redstone_music.removal_progress", progressPercent()).withStyle(ChatFormatting.AQUA));
        }
    }

    private int progressPercent() {
        if (planner == null || planner.totalWrites() == 0) {
            return 100;
        }
        return (int) (100L * planner.doneWrites() / planner.totalWrites());
    }

    // ---- 播放 ----

    /** 播放选项界面提交。mode: 0 经典 / 1 观赏 / 2 摄像机 / 3 强制停止 / 4 实时录制。 */
    public void handleStartPlayback(ServerPlayer player, int mode, int viewOffset, int fps, int width, int height) {
        switch (mode) {
            case 0, 1 -> {
                this.viewOffsetBlocks = Math.max(0, viewOffset);
                startPlayback(player);
            }
            case 2 -> {
                // 录制模式（合并原摄像机+实时录制）：世界实时运行，骑载具随波前，
                // 客户端内录画面与游戏声音
                this.viewOffsetBlocks = Math.max(0, viewOffset);
                startPlayback(player);
                if (state == State.RUNNING) {
                    this.recorder = player.getUUID();
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                            new com.autoredstonemusic.network.RecordingBeginPayload(songName));
                    Log.info("录制模式启动: 玩家={} 落后={} 格", player.getName().getString(), viewOffsetBlocks);
                }
            }
            case 3 -> stopPlayback(player, false);
        }
    }

    private void startPlayback(ServerPlayer player) {
        ServerLevel level = (ServerLevel) getLevel();
        if (!ensureCompiled(level)) {
            player.sendSystemMessage(Component.translatable("message.auto_redstone_music.song_missing", songName)
                    .withStyle(ChatFormatting.RED));
            return;
        }
        state = State.RUNNING;
        startTick = level.getGameTime();
        Log.info("播放启动: {} startTick={} 到达点 {} 尾刻={}",
                songName, startTick, compiled.arrivalTicks.length, lastNoteTick);
        followers.clear();
        forceChunk(level, true);
        cacheFollowPath();
        sweepStaleCarriers(level);
        // 所有轨道的启动源红石块同一刻放置 -> 全部波前同刻起跑
        for (int line = 0; line < geometry.lineCount; line++) {
            level.setBlock(geometry.cell(line, 0, 0, 0), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        }
        if (!mountCarrier(player)) {
            followers.add(player.getUUID()); // 兜底：载具不可用时退回瞬移跟随
        }
        teleportToStart(player); // 载具就绪后一次性定位（含落后偏移与行进朝向）
        player.sendOverlayMessage(Component.translatable("message.auto_redstone_music.started", songName)
                .withStyle(ChatFormatting.GREEN));
        setChanged();
    }

    private void stopPlayback(@Nullable ServerPlayer player, boolean auto) {
        ServerLevel level = (ServerLevel) getLevel();
        if (geometry != null) {
            for (int line = 0; line < geometry.lineCount; line++) {
                BlockPos source = geometry.cell(line, 0, 0, 0);
                if (level.getBlockState(source).is(Blocks.REDSTONE_BLOCK)) {
                    level.removeBlock(source, false);
                }
            }
        }
        sweepAt = level.getGameTime() + 2; // 延迟清扫，等排空波启动
        sweepCursorLine = 0;
        sweepCursorCell = 0;
        // 通知录制者立即收尾并保存视频
        if (recorder != null) {
            ServerPlayer rp = level.getServer().getPlayerList().getPlayer(recorder);
            if (rp != null) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(rp,
                        new com.autoredstonemusic.network.RecordingStopPayload());
            }
            recorder = null;
        }
        followers.clear();
        discardAllCarriers();
        state = State.IDLE;
        Log.info("播放停止: {} 原因={}", songName, auto ? "自然结束" : "手动");
        if (player != null) {
            player.sendOverlayMessage(Component.translatable(auto
                    ? "message.auto_redstone_music.finished"
                    : "message.auto_redstone_music.stopped").withStyle(ChatFormatting.YELLOW));
        }
        setChanged();
    }

    /** 播放结束/停止后的显示位清扫：把链上机械层全部写回"未通电"状态，跳过自然排空的分钟级延迟。 */
    private void sweepStep(ServerLevel level) {
        if (geometry == null || compiled == null || arrangement == null) {
            sweepAt = -1;
            forceChunk(level, false);
            return;
        }
        int budget = Config.BUILD_BATCH;
        while (budget-- > 0) {
            List<ChainCompiler.ChainCell> cells = compiled.lineCells.get(sweepCursorLine);
            if (sweepCursorCell >= cells.size()) {
                sweepCursorLine++;
                sweepCursorCell = 0;
                if (sweepCursorLine >= compiled.lineCells.size()) {
                    sweepAt = -1;
                    forceChunk(level, false);
                    setChanged();
                    return;
                }
                continue;
            }
            ChainCompiler.ChainCell cell = cells.get(sweepCursorCell++);
            switch (cell.kind) {
                case DUST -> {
                    safeSet(level, geometry.cell(sweepCursorLine, cell.z, 0, 0), RegionPlanner.wireOffState());
                    int mirror = geometry.noteSideSign(sweepCursorLine);
                    for (ChainCompiler.SideNote side : cell.sideNotes) {
                        int eff = side.side() * mirror;
                        safeSet(level, geometry.cell(sweepCursorLine, cell.z, eff, 0),
                                RegionPlanner.precisionRepeaterOffState(geometry.sideRepeaterFacing(eff), side.delayGt()));
                        safeSet(level, geometry.cell(sweepCursorLine, cell.z, eff * 2, 0), RegionPlanner.noteOffState(side.note()));
                    }
                }
                case REPEATER -> safeSet(level, geometry.cell(sweepCursorLine, cell.z, 0, 0),
                        RegionPlanner.precisionRepeaterOffState(geometry.chainRepeaterFacing(), cell.delay));
                case SOURCE -> {
                    // 已在 stopPlayback 中移除
                }
            }
        }
        setChanged();
    }

    private void safeSet(ServerLevel level, BlockPos pos, BlockState state) {
        if (level.isLoaded(pos)) {
            level.setBlock(pos, state, 3);
        }
    }

    // ---- 每刻驱动 ----

    public static void serverTick(Level level, BlockPos pos, BlockState state, MusicControllerBlockEntity be) {
        if (level.isClientSide()) {
            return;
        }
        ServerLevel serverLevel = (ServerLevel) level;
        switch (be.state) {
            case BUILDING -> be.buildStep(serverLevel);
            case REMOVING -> be.removalStep(serverLevel);
            case RUNNING -> be.playbackStep(serverLevel);
            case IDLE -> {
                if (be.sweepAt >= 0 && level.getGameTime() >= be.sweepAt) {
                    be.sweepStep(serverLevel);
                }
            }
        }
    }

    private void buildStep(ServerLevel level) {
        // 停滞看门狗：BUILDING 状态下进度长时间不动 = 出了问题，主动报错而不是静默卡死
        if (planner != null && planner.doneWrites() == lastDoneWrites) {
            if (level.getGameTime() - lastProgressGameTime > 200) {
                Log.error("铺设停滞! 进度 {}/{} 已 {} 刻未推进，采样诊断: 控制器存活={}。已中止并回退。",
                        planner.doneWrites(), planner.totalWrites(), level.getGameTime() - lastProgressGameTime,
                        level.getBlockState(worldPosition).getBlock() == ModRegistries.MUSIC_CONTROLLER.get());
                abortBuild(level, "build stalled");
                return;
            }
        } else {
            lastDoneWrites = planner != null ? planner.doneWrites() : 0;
            lastProgressGameTime = level.getGameTime();
        }
        if (!level.getBlockState(worldPosition).is(ModRegistries.MUSIC_CONTROLLER.get())) {
            Log.error("铺设中止: 控制器方块已不在原位（{}），构建失去宿主。", worldPosition);
            planner = null;
            state = State.IDLE;
            return;
        }
        try {
            // 先重试上刻因区块未加载而暂存的写入
            if (pendingWrite != null) {
                if (!level.isLoaded(pendingWrite.pos())) {
                    forceChunkAt(level, pendingWrite.pos()); // 触发异步加载，本刻结束
                    return;
                }
                level.setBlock(pendingWrite.pos(), pendingWrite.state(), 3);
                pendingWrite = null;
            }
            long deadline = System.nanoTime() + Config.BUILD_TIME_BUDGET_NANOS;
            int writes = 0;
            int applied = 0;
            int skipped = 0;
            int failures = 0;
            while (planner != null && !planner.isDone()
                    && writes < Config.BUILD_MAX_WRITES_PER_TICK
                    && (writes < Config.BUILD_BATCH || System.nanoTime() < deadline)) {
                RegionPlanner.Write write = planner.next();
                writes++;
                if (!level.isLoaded(write.pos())) {
                    // 目标区块未加载：暂存 + 触发【异步】加载（滑动窗口），本刻到此为止。
                    // 直接 setBlock 到未加载区块会触发同步加载——实测单次可卡 14 秒。
                    pendingWrite = write;
                    forceChunkAt(level, write.pos());
                    break;
                }
                BlockState current = level.getBlockState(write.pos());
                if (current == write.state()) {
                    // 引用相等 = 完全相同的已驻留状态（含"向纯空 section 写空气"），跳过即完成
                    skipped++;
                    continue;
                }
                if (level.setBlock(write.pos(), write.state(), 3)) {
                    applied++;
                } else if (!write.state().isAir()) {
                    // 只有非空气写入失败才算真故障（原版对"空气写入已空 section"返回 false）
                    failures++;
                    if (failures <= 3) {
                        Log.warn("setBlock 失败: pos={} 目标={} 现状={}",
                                write.pos().toShortString(), write.state().getBlock(), current.getBlock());
                    }
                }
            }
            if (failures > 0) {
                Log.warn("本刻写入: 应用 {} 跳过(无变化) {} 失败 {}（进度 {}/{})",
                        applied, skipped, failures, planner == null ? -1 : planner.doneWrites(),
                        planner == null ? -1 : planner.totalWrites());
            } else {
                Log.debug("本刻写入: 应用 {} 跳过 {}（进度 {}/{})",
                        applied, skipped, planner == null ? -1 : planner.doneWrites(),
                        planner == null ? -1 : planner.totalWrites());
            }
        } catch (Throwable t) {
            Log.error("铺设循环异常! 进度 {}/{}，已中止并回退。".formatted(
                    planner == null ? -1 : planner.doneWrites(), planner == null ? -1 : planner.totalWrites()), t);
            abortBuild(level, "exception in build loop");
            return;
        }
        if (firstBuildJump) {
            firstBuildJump = false;
            Log.info("构建循环开始运转（首个 ticker tick）");
        }
        if (planner != null && level.getGameTime() - lastReportTick >= 60) {
            lastReportTick = level.getGameTime();
            Log.info("铺设进度: {}/{} ({}%)", planner.doneWrites(), planner.totalWrites(), progressPercent());
        }
        if (planner != null && planner.isDone()) {
            planner = null;
            pendingWrite = null;
            releaseForcedChunks(level);
            state = State.IDLE;
            if (placer != null) {
                ServerPlayer player = level.getServer().getPlayerList().getPlayer(placer);
                if (player != null) {
                    player.sendOverlayMessage(Component.translatable("message.auto_redstone_music.build_done", songName)
                            .withStyle(ChatFormatting.GREEN));
                }
            }
        } else if (placer != null && level.getGameTime() - lastReportTick >= 40) {
            lastReportTick = level.getGameTime();
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(placer);
            if (player != null) {
                player.sendOverlayMessage(Component.translatable("message.auto_redstone_music.build_progress",
                        songName, progressPercent()));
            }
        }
        setChanged();
    }

    private void removalStep(ServerLevel level) {
        try {
            int budget = Config.BUILD_BATCH;
            while (budget-- > 0 && planner != null && !planner.isDone()) {
                RegionPlanner.Write write = planner.next();
                level.setBlock(write.pos(), write.state(), 3);
            }
        } catch (Throwable t) {
            Log.error("拆除循环异常! 已尝试恢复为 IDLE。", t);
            planner = null;
            state = State.IDLE;
            return;
        }
        if (planner != null && planner.isDone()) {
            planner = null;
            releaseForcedChunks(level);
            level.removeBlock(worldPosition, false); // 触发 onRemove → BE 自毁
        } else if (placer != null && level.getGameTime() - lastReportTick >= 40) {
            lastReportTick = level.getGameTime();
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(placer);
            if (player != null) {
                player.sendOverlayMessage(Component.translatable("message.auto_redstone_music.removal_progress",
                        progressPercent()));
            }
        }
    }

    private void playbackStep(ServerLevel level) {
        if (sweepAt >= 0 && level.getGameTime() >= sweepAt) {
            sweepStep(level);
            if (state != State.RUNNING) {
                return; // 播放已停止
            }
        }
        long t = level.getGameTime() - startTick;
        if (t < 0) {
            return;
        }
        if (t > lastNoteTick + Config.TAIL_TICKS) {
            stopPlayback(null, true);
            return;
        }
        if (!carriers.isEmpty()) {
            // 连续移动：每刻把载具放到波前的精确插值位置（玩家骑乘，无传送顿挫；
            // 速度随音乐密度呼吸——长音漂、急奏飞）
            glideCarriers(level, (double) t);
        }
        if (!followers.isEmpty()) {
            teleportFollowers(level, (int) t); // 兜底路径
        }
    }

    /** 载具滑行：玩家骑在隐形标记盔甲架上，位置=波前的连续插值（含压缩间隙的变速段）。 */
    private void glideCarriers(ServerLevel level, double slotF) {
        double z = followZf(slotF) - viewOffsetBlocks;
        double x = geometry.origin.getX() + 0.5 + geometry.facing.getStepX() * z;
        double zz = geometry.origin.getZ() + 0.5 + geometry.facing.getStepZ() * z;
        float yaw = geometry.facing.toYRot();
        java.util.Iterator<java.util.Map.Entry<UUID, ArmorStand>> it = carriers.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<UUID, ArmorStand> entry = it.next();
            ArmorStand stand = entry.getValue();
            ServerPlayer rider = level.getServer().getPlayerList().getPlayer(entry.getKey());
            boolean riderGone = rider == null || rider.level() != level;
            boolean dismounted = rider != null && rider.getRootVehicle() != stand;
            if (riderGone || dismounted || !stand.isAlive()) {
                if (stand.isAlive()) {
                    stand.ejectPassengers();
                    stand.discard();
                }
                it.remove();
                Log.debug("跟随者脱离: {}（下线/下车）", entry.getKey());
                continue;
            }
            stand.setPos(x, stand.getY(), zz);
            stand.setYRot(yaw);
        }
    }

    /** 缓存"到达时刻 → 轴向偏移"，供每刻插值。 */
    private void cacheFollowPath() {
        if (compiled == null) {
            return;
        }
        arrivalTickCache = compiled.arrivalTicks.clone();
        arrivalZCache = new double[compiled.arrivalZ.length];
        for (int i = 0; i < compiled.arrivalZ.length; i++) {
            arrivalZCache[i] = compiled.arrivalZ[i];
        }
    }

    /** 时刻（可为小数）→ 世界轴向偏移：相邻到达点间线性插值，保证连续无跳变。 */
    private double followZf(double tick) {
        if (arrivalTickCache.length == 0) {
            return Config.CHAIN_START_OFFSET;
        }
        if (tick <= arrivalTickCache[0]) {
            return arrivalZCache[0];
        }
        int last = arrivalTickCache.length - 1;
        if (tick >= arrivalTickCache[last]) {
            return arrivalZCache[last];
        }
        for (int i = 1; i < arrivalTickCache.length; i++) {
            if (arrivalTickCache[i] >= tick) {
                double f = (tick - arrivalTickCache[i - 1]) / (arrivalTickCache[i] - arrivalTickCache[i - 1]);
                return arrivalZCache[i - 1] + (arrivalZCache[i] - arrivalZCache[i - 1]) * f;
            }
        }
        return arrivalZCache[last];
    }

    /** 生成隐形载具并让玩家骑乘（失败返回 false，调用方退回瞬移跟随）。 */
    private boolean mountCarrier(ServerPlayer player) {
        ServerLevel level = (ServerLevel) getLevel();
        if (geometry == null) {
            return false;
        }
        ArmorStand stand = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (stand == null) {
            return false;
        }
        double z = followZf(0) - viewOffsetBlocks;
        stand.setInvisible(true);
        stand.setSilent(true);
        stand.setNoGravity(true);
        stand.setInvulnerable(true);
        stand.setNoBasePlate(true);
        stand.getPersistentData().putBoolean(CARRY_TAG, true);
        double attachY = player.getVehicleAttachmentPoint(stand).y; // 乘客相对载具的偏移，动态补偿
        stand.setPos(geometry.origin.getX() + 0.5 + geometry.facing.getStepX() * z,
                geometry.origin.getY() - attachY,
                geometry.origin.getZ() + 0.5 + geometry.facing.getStepZ() * z);
        stand.setYRot(geometry.facing.toYRot());
        if (!level.addFreshEntity(stand)) {
            return false;
        }
        if (!player.startRiding(stand, true, true)) {
            stand.discard();
            return false;
        }
        carriers.put(player.getUUID(), stand);
        Log.debug("载具跟随已挂载: {}", player.getName().getString());
        return true;
    }

    /** 清理历史残留载具（上次播放异常退出遗留的隐形盔甲架）。 */
    private void sweepStaleCarriers(ServerLevel level) {
        if (geometry == null) {
            return;
        }
        BlockPos a = geometry.axisPos(0, 0);
        BlockPos b = geometry.axisPos(compiled != null ? compiled.maxZ + 2 : 64, 0);
        AABB box = new AABB(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()))
                .inflate(RegionGeometry.SWEEP_MARGIN);
        for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class, box)) {
            if (stand.getPersistentData().getBoolean(CARRY_TAG).orElse(false)) {
                Log.debug("清理残留载具: {}", stand.position());
                stand.discard();
            }
        }
    }

    private void discardAllCarriers() {
        for (ArmorStand stand : carriers.values()) {
            stand.ejectPassengers();
            stand.discard();
        }
        carriers.clear();
    }

    // ---- 玩家搬运 ----

    private void teleportToStart(ServerPlayer player) {
        // 落后 viewOffsetBlocks 格定位；朝向固定为行进方向（默认视角 = 隧道远方）
        int z = Config.CHAIN_START_OFFSET - viewOffsetBlocks;
        BlockPos front = geometry.axisPos(z, 0);
        player.connection.teleport(front.getX() + 0.5, front.getY(), front.getZ() + 0.5,
                geometry.facing.toYRot(), 0.0F);
    }

    private void teleportFollowers(ServerLevel level, int tick) {
        MinecraftServer server = level.getServer();
        for (UUID uuid : List.copyOf(followers)) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null || player.isShiftKeyDown() || player.level() != level) {
                followers.remove(uuid);
                continue;
            }
            int z = (int) Math.round(followZf(tick));
            BlockPos target = geometry.axisPos(z, 0);
            player.connection.teleport(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, player.getYRot(), player.getXRot());
        }
    }

    // ---- 拆除 ----

    private void startRemoval(ServerPlayer player) {
        ServerLevel level = (ServerLevel) getLevel();
        geometry = new RegionGeometry(worldPosition, facing, lineCount);
        planner = RegionPlanner.removal(geometry);
        state = State.REMOVING;
        if (ensureCompiled(level)) {
            java.util.Map<Long, Long> channel = buildChannelEntries(arrangement, compiled, geometry);
            PitchChannel.removeAll(level, List.copyOf(channel.keySet()));
            PitchChannelSync.sendToTracking(level, worldPosition, channel, true);
        } else {
            Log.warn("拆除时编排数据缺失，音高通道中的旧登记将残留（无害，触发校验会自愈）");
        }
        player.sendOverlayMessage(Component.translatable("message.auto_redstone_music.removing",
                planner.totalWrites()).withStyle(ChatFormatting.YELLOW));
        setChanged();
    }

    // ---- 重建与清理 ----

    /** 确保编配与链编译可用（放置后/世界重载后惰性重建）。 */
    private boolean ensureCompiled(ServerLevel level) {
        if (compiled != null && geometry != null) {
            return true;
        }
        byte[] bytes = SongLibrary.get(level.getServer()).get(songHash);
        if (bytes == null) {
            Log.error("歌曲库中找不到编排数据: hash={} —— 世界存档与歌曲库可能不同步", songHash);
            return false;
        }
        Log.debug("从歌曲库载入编排: hash={} {}", songHash, Log.bytesDiag(bytes));
        try {
            arrangement = SongArrangement.fromBytes(bytes);
            compiled = ChainCompiler.compile(arrangement);
            if (geometry == null) {
                geometry = new RegionGeometry(worldPosition, facing, arrangement.lines.size());
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 控制器方块被破坏时清理世界残留（启动源/波前/强载）。不依赖编配数据。 */
    public void shutdownWorldPieces() {
        if (getLevel() instanceof ServerLevel level && geometry != null) {
            releaseForcedChunks(level);
            for (int line = 0; line < geometry.lineCount; line++) {
                BlockPos source = geometry.cell(line, 0, 0, 0);
                if (level.getBlockState(source).is(Blocks.REDSTONE_BLOCK)) {
                    level.removeBlock(source, false);
                }
            }
        }
        if (getLevel() instanceof ServerLevel serverLevel) {
            discardAllCarriers();
            forceChunk(serverLevel, false);
        }
        followers.clear();
    }

    private void forceChunk(ServerLevel level, boolean add) {
        level.setChunkForced(worldPosition.getX() >> 4, worldPosition.getZ() >> 4, add);
    }

    // ---- NBT ----

    /** 控制器方块被移除前：清理启动源/波前/强载（BE 此时仍然存活）。 */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        shutdownWorldPieces();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putString("songHash", songHash);
        output.putString("songName", songName);
        output.putString("facing", facing.getName());
        output.putInt("lineCount", lineCount);
        output.putInt("viewOffset", viewOffsetBlocks);
        output.putInt("lastNoteTick", lastNoteTick);
        output.putInt("state", state.ordinal());
        if (placer != null) {
            output.putString("placer", placer.toString());
        }
        output.putLong("startTick", startTick);
        ValueOutput.TypedOutputList<String> list = output.list("followers", Codec.STRING);
        for (UUID uuid : followers) {
            list.add(uuid.toString());
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        songHash = input.getStringOr("songHash", "");
        songName = input.getStringOr("songName", "");
        facing = Direction.byName(input.getStringOr("facing", "north"));
        if (facing == null) {
            facing = Direction.NORTH;
        }
        lineCount = input.getIntOr("lineCount", 1);
        viewOffsetBlocks = input.getIntOr("viewOffset", 0);
        lastNoteTick = input.getIntOr("lastNoteTick", 0);
        int stateOrdinal = input.getIntOr("state", 0);
        startTick = input.getLongOr("startTick", 0);
        String placerId = input.getStringOr("placer", "");
        if (!placerId.isEmpty()) {
            try {
                placer = UUID.fromString(placerId);
            } catch (IllegalArgumentException ignored) {
            }
        }
        followers.clear();
        for (String s : input.listOrEmpty("followers", Codec.STRING)) {
            try {
                followers.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
            }
        }
        state = State.values()[Math.min(stateOrdinal, State.values().length - 1)];
        if (state == State.RUNNING || state == State.BUILDING) {
            // 世界重载时中断进行中的构建/播放：安全复位（移除启动源红石块，避免残留常亮链路）
            if (getLevel() instanceof ServerLevel serverLevel) {
                geometry = new RegionGeometry(worldPosition, facing, lineCount);
                for (int line = 0; line < geometry.lineCount; line++) {
                    BlockPos source = geometry.cell(line, 0, 0, 0);
                    if (serverLevel.getBlockState(source).is(Blocks.REDSTONE_BLOCK)) {
                        serverLevel.removeBlock(source, false);
                    }
                }
            }
            state = State.IDLE;
        }
    }
}
