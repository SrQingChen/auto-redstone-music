package com.autoredstonemusic.arrange;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GM 音色/鼓组 → 26.1.2 音符盒乐器映射。
 *
 * <p>映射参照 OpenNBS 的 GM 表思路，并利用 26.1 新增乐器（GUITAR、TRUMPET 四态）扩充。
 * 未命中的音色回退 HARP。表内全部使用乐器序列化名（字符串），因此本类除
 * {@link #bySerializedName}/{@link #underBlockFor}（world 层专用）外不触碰 MC 类，
 * 编排管线可在游戏外独立测试。
 *
 * <p>音符盒的 INSTRUMENT 方块属性由我们显式写入；下方方块按"乐器 → 候选方块"表放置，
 * 保证原版 updateShape 重推导结果一致（26.1.2 乐器来自 BlockBehaviour.Properties.instrument(...)，
 * 候选方块在运行时从注册表反查，偏好表只影响美观）。
 */
public final class InstrumentMap {
    private InstrumentMap() {}

    public static final String HARP = "harp";

    /** GM 128 音色 → 乐器（序列化名）。索引 = program。 */
    private static final String[] GM_TABLE = buildGmTable();

    private static String[] buildGmTable() {
        String[] t = new String[128];
        java.util.Arrays.fill(t, HARP);
        // 钢琴（GM 0/1）= HARP：柔和拨弦最接近钢琴本色（用户定稿）；走音钢琴的滑稽感用 PLING
        t[2] = "bit"; t[3] = "pling"; t[4] = "bit"; t[5] = "bit"; t[6] = "pling"; t[7] = "bit";
        t[8] = "bell"; t[9] = "bell"; t[10] = "chime"; t[11] = "iron_xylophone"; t[12] = "xylophone";
        t[13] = "xylophone"; t[14] = "bell"; t[15] = "banjo";
        t[16] = "bit"; t[17] = "bit"; t[18] = "bit"; t[19] = "bit";
        t[20] = "flute"; t[21] = "flute"; t[22] = "flute"; t[23] = "flute";
        t[24] = "guitar"; t[25] = "guitar"; t[26] = "guitar"; t[27] = "guitar"; t[28] = "guitar";
        t[29] = "guitar"; t[30] = "bit"; t[31] = "guitar";
        t[32] = "bass"; t[33] = "bass"; t[34] = "bass"; t[35] = "bass"; t[36] = "bass"; t[37] = "bass";
        t[38] = "bit"; t[39] = "bit";
        t[40] = "flute"; t[41] = "flute"; t[42] = "flute"; t[43] = "flute"; t[44] = "flute";
        t[45] = "pling"; t[47] = "basedrum";
        t[48] = "flute"; t[49] = "flute"; t[50] = "flute"; t[51] = "flute"; t[52] = "flute";
        t[53] = "flute"; t[54] = "bit"; t[55] = "pling";
        t[56] = "trumpet"; t[57] = "trumpet"; t[58] = "trumpet_weathered"; t[59] = "trumpet_exposed";
        t[60] = "trumpet"; t[61] = "trumpet"; t[62] = "trumpet_oxidized"; t[63] = "trumpet_oxidized";
        t[64] = "flute"; t[65] = "flute"; t[66] = "flute"; t[67] = "didgeridoo"; t[68] = "flute"; t[69] = "flute";
        for (int i = 70; i <= 76; i++) t[i] = "flute";
        t[77] = "didgeridoo"; t[78] = "flute"; t[79] = "flute";
        for (int i = 80; i <= 95; i++) t[i] = "bit";
        t[96] = "banjo"; t[97] = "banjo"; t[98] = "banjo"; t[99] = "banjo"; t[100] = "pling";
        t[101] = "didgeridoo"; t[102] = "guitar"; t[103] = "didgeridoo";
        t[104] = "bell"; t[105] = "cow_bell"; t[106] = "pling"; t[107] = "cow_bell";
        t[108] = "basedrum"; t[109] = "basedrum"; t[110] = "basedrum"; t[111] = "hat";
        for (int i = 112; i <= 119; i++) t[i] = "hat";
        t[120] = "snare"; t[121] = "snare"; t[122] = "hat"; t[123] = "hat"; t[124] = "cow_bell";
        t[125] = "hat"; t[126] = "hat"; t[127] = "hat";
        return t;
    }

    /** programKey（program + 128*bank）→ 乐器序列化名。 */
    public static String programInstrumentName(int programKey) {
        return GM_TABLE[Math.floorMod(programKey, 128)];
    }

    public static String gmName(int programKey) {
        return "GM " + Math.floorMod(programKey, 128);
    }

    /** 乐器音域锚点（MIDI 中央值，折叠时以它为基准）。 */
    public static int anchorMidiForName(String instrument) {
        return switch (instrument) {
            case "bass" -> 45;
            case "basedrum", "snare", "hat", "cow_bell" -> 60;
            case "xylophone", "iron_xylophone", "bell", "chime", "pling" -> 72;
            case "didgeridoo" -> 52;
            case "trumpet", "trumpet_exposed", "trumpet_oxidized", "trumpet_weathered" -> 62;
            default -> 66; // F#4 = 音符盒 note 12 中心
        };
    }

    public record DrumHit(String instrument, int note) {}

    /** GM 鼓组键位（通道 10）→ 乐器 + 固定音高。 */
    public static DrumHit forDrumKey(int key) {
        return switch (key) {
            case 35, 36 -> new DrumHit("basedrum", 0);
            case 37 -> new DrumHit("snare", 12);
            case 38 -> new DrumHit("snare", 0);
            case 39 -> new DrumHit("snare", 14);
            case 40 -> new DrumHit("snare", 4);
            case 41 -> new DrumHit("basedrum", 6);
            case 42 -> new DrumHit("hat", 8);
            case 43 -> new DrumHit("basedrum", 8);
            case 44 -> new DrumHit("hat", 4);
            case 45 -> new DrumHit("basedrum", 10);
            case 46 -> new DrumHit("hat", 12);
            case 47, 48 -> new DrumHit("basedrum", 12);
            case 49, 55 -> new DrumHit("hat", 20);
            case 50 -> new DrumHit("basedrum", 14);
            case 51, 59 -> new DrumHit("bell", 12);
            case 52 -> new DrumHit("hat", 24);
            case 53 -> new DrumHit("cow_bell", 12);
            case 54 -> new DrumHit("hat", 16);
            case 56 -> new DrumHit("cow_bell", 0);
            case 57 -> new DrumHit("hat", 24);
            case 58 -> new DrumHit("snare", 2);
            case 60, 61 -> new DrumHit("basedrum", 16);
            case 62, 63 -> new DrumHit("basedrum", 18);
            case 64, 65 -> new DrumHit("basedrum", 20);
            case 66, 67 -> new DrumHit("snare", 16);
            case 68, 69 -> new DrumHit("snare", 18);
            default -> {
                if (key >= 70 && key <= 79) yield new DrumHit("cow_bell", 8);
                if (key >= 80 && key <= 87) yield new DrumHit("hat", 6);
                yield new DrumHit("hat", 12);
            }
        };
    }

    // ---- 乐器 → 下方方块（运行时反查注册表，偏好表只影响美观）----

    private static final Map<String, String> PREFERRED = Map.ofEntries(
            Map.entry("harp", ""), // 空 = 用轨道基座（基座默认 HARP）
            Map.entry("bass", "minecraft:oak_planks"),
            Map.entry("basedrum", "minecraft:stone"),
            Map.entry("snare", "minecraft:sand"),
            Map.entry("hat", "minecraft:glass"),
            Map.entry("flute", "minecraft:clay"),
            Map.entry("bell", "minecraft:gold_block"),
            Map.entry("guitar", "minecraft:white_wool"),
            Map.entry("chime", "minecraft:packed_ice"),
            Map.entry("xylophone", "minecraft:bone_block"),
            Map.entry("iron_xylophone", "minecraft:iron_block"),
            Map.entry("cow_bell", "minecraft:soul_sand"),
            Map.entry("didgeridoo", "minecraft:pumpkin"),
            Map.entry("bit", "minecraft:emerald_block"),
            Map.entry("banjo", "minecraft:hay_block"),
            Map.entry("pling", "minecraft:glowstone"),
            Map.entry("trumpet", "minecraft:copper_block"),
            Map.entry("trumpet_exposed", "minecraft:exposed_copper"),
            Map.entry("trumpet_weathered", "minecraft:weathered_copper"),
            Map.entry("trumpet_oxidized", "minecraft:oxidized_copper")
    );

    private static Map<String, List<String>> byInstrument; // 惰性构建（避免游戏外加载注册表）

    private static Map<String, List<String>> reverseLookup() {
        Map<String, List<String>> map = byInstrument;
        if (map == null) {
            map = new HashMap<>();
            for (Block block : BuiltInRegistries.BLOCK) {
                String name = block.defaultBlockState().instrument().getSerializedName();
                if (!name.equals(HARP)) {
                    Identifier id = BuiltInRegistries.BLOCK.getKey(block);
                    map.computeIfAbsent(name, k -> new ArrayList<>()).add(id.toString());
                }
            }
            byInstrument = map;
        }
        return map;
    }

    /** 乐器对应的音符盒下方方块 id。空 = 用轨道基座（仅 HARP）。 */
    public static String underBlockFor(String instrument) {
        String preferred = PREFERRED.getOrDefault(instrument, "");
        if (preferred.isEmpty()) {
            return "";
        }
        List<String> candidates = reverseLookup().get(instrument);
        if (candidates != null) {
            if (candidates.contains(preferred)) {
                return preferred;
            }
            if (!candidates.isEmpty()) {
                return candidates.get(0);
            }
        }
        return "";
    }

    // ---- 全采样接管：GM 音色 → 采样族（0-127）----

    private static final String[] GM_FAMILY = buildFamilyTable();

    private static String[] buildFamilyTable() {
        String[] t = new String[128];
        java.util.Arrays.fill(t, "piano");
        // 钢琴族
        for (int i = 0; i <= 5; i++) t[i] = "piano";
        t[6] = "harpsichord"; t[7] = "harpsichord";            // 大键琴/古钢琴
        t[8] = "glockenspiel"; t[9] = "glockenspiel"; t[10] = "glockenspiel"; // 钢片琴/钟琴/音乐盒
        t[11] = "vibraphone"; t[12] = "marimba"; t[13] = "xylophone"; t[14] = "tubular";
        t[15] = "harp";                                        // 扬琴
        for (int i = 16; i <= 20; i++) t[i] = "organ";         // 风琴
        t[21] = "organ"; t[22] = "sax"; t[23] = "organ";       // 手风琴/口琴/探戈手风琴
        for (int i = 24; i <= 31; i++) t[i] = "guitar";        // 吉他族
        t[32] = "contrabass"; t[33] = "contrabass";            // 原声贝斯
        for (int i = 34; i <= 39; i++) t[i] = "guitar";        // 电贝斯 ≈ 低音拨弦
        t[40] = "violin"; t[41] = "viola"; t[42] = "cello"; t[43] = "contrabass";
        t[44] = "violin_ens"; t[45] = "harp"; t[46] = "harp";  // 震音/拨弦/竖琴
        t[47] = "drum";                                        // 定音鼓（打击族，键重映射）
        for (int i = 48; i <= 55; i++) t[i] = "violin_ens";    // 弦乐合奏/合唱
        t[56] = "trumpet"; t[57] = "trombone"; t[58] = "tuba"; t[59] = "trumpet";
        t[60] = "horn"; t[61] = "trumpet";                     // 圆号/铜管组
        t[62] = "synth"; t[63] = "synth";                      // 合成铜管
        for (int i = 64; i <= 67; i++) t[i] = "sax";           // 萨克斯族
        t[68] = "oboe"; t[69] = "oboe"; t[70] = "bassoon"; t[71] = "clarinet";
        t[72] = "piccolo"; t[73] = "flute";
        for (int i = 74; i <= 77; i++) t[i] = "recorder";      // 竖笛/排箫/陶笛
        t[78] = "piccolo"; t[79] = "recorder";
        for (int i = 80; i <= 87; i++) t[i] = "synth";         // 合成主音
        for (int i = 88; i <= 95; i++) t[i] = "organ";         // 合成铺底
        for (int i = 96; i <= 99; i++) t[i] = "harp";          // 民族拨弦
        t[100] = "marimba"; t[101] = "recorder"; t[102] = "violin"; t[103] = "oboe";
        t[104] = "glockenspiel"; t[105] = "drum"; t[106] = "marimba"; t[107] = "drum";
        t[108] = "drum"; t[109] = "drum"; t[110] = "drum"; t[111] = "drum";
        for (int i = 112; i <= 118; i++) t[i] = "synth";       // 音效
        t[119] = "drum"; t[120] = "guitar"; t[121] = "recorder"; t[122] = "synth";
        t[123] = "piccolo"; t[124] = "synth"; t[125] = "synth"; t[126] = "drum"; t[127] = "drum";
        return t;
    }

    /** 映射到打击族时需要覆盖的采样键（0=用音符本身）。 */
    private static final int[] DRUM_KEY_OVERRIDE = new int[128];

    static {
        DRUM_KEY_OVERRIDE[47] = 41;   // 定音鼓 → 低音鼓
        DRUM_KEY_OVERRIDE[105] = 56;  // 拉丁打铃 → 牛铃
        DRUM_KEY_OVERRIDE[107] = 76;  // 木鱼 → 高木块
        DRUM_KEY_OVERRIDE[108] = 41;  // 太鼓
        DRUM_KEY_OVERRIDE[109] = 48;  // 旋律嗵鼓
        DRUM_KEY_OVERRIDE[110] = 40;  // 合成鼓
        DRUM_KEY_OVERRIDE[111] = 49;  // 反镲
        DRUM_KEY_OVERRIDE[119] = 38;  // 枪声 → 军鼓
        DRUM_KEY_OVERRIDE[126] = 39;  // 掌声 → 拍手
        DRUM_KEY_OVERRIDE[127] = 38;
    }

    /** GM 音色 → 采样族名。 */
    public static String programFamily(int programKey) {
        return GM_FAMILY[Math.floorMod(programKey, 128)];
    }

    /** 打击族采样键重映射（不可用键 → 最近的可用键），同时处理超界。 */
    public static int drumSampleKey(int gmKey) {
        int k = Math.max(0, Math.min(127, gmKey));
        if (k < 35) {
            return 35;
        }
        if (k > 81) {
            return 81;
        }
        return switch (k) {
            case 43 -> 41;
            case 45 -> 47;
            case 52 -> 49;
            case 55 -> 49;
            case 59 -> 51;
            case 65 -> 64;
            case 67 -> 66;
            case 68 -> 64;
            case 71, 72, 78, 79 -> 80;
            case 82, 83, 84, 85, 86, 87 -> 81;
            default -> k;
        };
    }

    public static int programDrumOverride(int programKey) {
        return DRUM_KEY_OVERRIDE[Math.floorMod(programKey, 128)];
    }

    /** 由序列化名解析乐器枚举（world 层专用）。 */
    public static NoteBlockInstrument bySerializedName(String name) {
        for (NoteBlockInstrument instrument : NoteBlockInstrument.values()) {
            if (instrument.getSerializedName().equals(name)) {
                return instrument;
            }
        }
        return NoteBlockInstrument.HARP;
    }
}
