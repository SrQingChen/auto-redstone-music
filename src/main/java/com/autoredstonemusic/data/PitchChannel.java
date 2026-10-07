package com.autoredstonemusic.data;

import com.autoredstonemusic.AutoRedstoneMusicMod;
import com.autoredstonemusic.Log;
import com.autoredstonemusic.registry.ModSounds;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 音高通道：坐标 → 音符发声参数（采样索引/真实 MIDI 键/力度/折叠校验音）。
 *
 * <p>铺设时写入（服务端 SavedData 持久化 + 同步给追踪客户端）；Mixin 拦截
 * {@code NoteBlock.triggerEvent} 时查询：有登记 → 播放 Salamander 钢琴采样
 * （真实音高 + 力度音量）并取消原版发声；无登记 → 100% 原版行为。
 *
 * <p><b>自愈校验</b>：登记里存有铺设时的折叠音（NOTE 属性）。触发时若方块当前的
 * NOTE 与登记不符（玩家手动调过音 / 活塞移动过），视为失效条目——删除并回落原版发声。
 *
 * <p>打包格式（long）：采样索引 5b | MIDI 键 7b | 力度 7b | 折叠音 5b。
 */
public final class PitchChannel {
    private PitchChannel() {}

    public static final class PitchData extends SavedData {
        public static final Codec<PitchData> CODEC = Codec.unboundedMap(Codec.STRING, Codec.LONG)
                .xmap(PitchData::new, d -> {
                    Map<String, Long> out = new HashMap<>();
                    for (Map.Entry<Long, Long> e : d.entries.entrySet()) {
                        out.put(Long.toString(e.getKey()), e.getValue());
                    }
                    return out;
                });

        public static final SavedDataType<PitchData> TYPE = new SavedDataType<>(
                Identifier.fromNamespaceAndPath(AutoRedstoneMusicMod.MODID, "note_pitch_channel"), PitchData::new, CODEC);

        private final Map<Long, Long> entries = new HashMap<>();

        public PitchData() {
        }

        private PitchData(Map<String, Long> from) {
            for (Map.Entry<String, Long> e : from.entrySet()) {
                try {
                    entries.put(Long.parseLong(e.getKey()), e.getValue());
                } catch (NumberFormatException ignored) {
                }
            }
        }

        public Map<Long, Long> snapshot() {
            return new HashMap<>(entries);
        }
    }

    // ---- 打包/解包（v3：族 6b | 增益 5b | 键 7b | 力度 7b | 折叠音 5b）----

    public static long pack(int familyId, int gainIdx, int midiKey, int velocity, int foldedNote) {
        return (foldedNote & 31L)
                | ((velocity & 127L) << 5)
                | ((midiKey & 127L) << 12)
                | ((gainIdx & 31L) << 19)
                | ((familyId & 63L) << 24);
    }

    public static int familyId(long packed) {
        return (int) ((packed >>> 24) & 63);
    }

    public static int gainIdx(long packed) {
        return (int) ((packed >>> 19) & 31);
    }

    public static int midiKey(long packed) {
        return (int) ((packed >>> 12) & 127);
    }

    public static int velocity(long packed) {
        return (int) ((packed >>> 5) & 127);
    }

    public static int foldedNote(long packed) {
        return (int) (packed & 31);
    }

    /**
     * 力度 → 播放音量（^1.35 曲线拉开动态）+ 编排期烘焙的增益（CC7/CC11 × 角色平衡）。
     * 最终音量钳制在 [0.05, 1.6]。
     */
    public static float volumeFor(int velocity, int gainIdx) {
        // 基准对齐原版音符盒音量量级（原版固定 3.0）：v=127 → 2.25，v=64 → 1.12
        float base = 0.25f + 2.0f * (float) Math.pow(velocity / 127.0f, 1.35);
        float gain = com.autoredstonemusic.registry.SampleTable.gainOf(gainIdx);
        return Math.max(0.08f, Math.min(3.0f, base * gain));
    }

    // ---- 服务端存取 ----

    public static PitchData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(PitchData.TYPE);
    }

    public static void putAll(ServerLevel level, Map<Long, Long> entries) {
        if (entries.isEmpty()) {
            return;
        }
        PitchData data = get(level);
        data.entries.putAll(entries);
        data.setDirty();
        Log.debug("音高通道写入 {} 条（累计 {}）", entries.size(), data.entries.size());
    }

    public static void removeAll(ServerLevel level, List<Long> positions) {
        if (positions.isEmpty()) {
            return;
        }
        PitchData data = get(level);
        int before = data.entries.size();
        for (Long pos : positions) {
            data.entries.remove(pos);
        }
        data.setDirty();
        Log.debug("音高通道移除 {} 条（{} -> {}）", positions.size(), before, data.entries.size());
    }

    /** 查询（服务端）。 */
    public static Long lookup(ServerLevel level, BlockPos pos) {
        return get(level).entries.get(pos.asLong());
    }

    /** 全量快照（登录同步用）。 */
    public static Map<Long, Long> snapshot(ServerLevel level) {
        return get(level).snapshot();
    }
}
