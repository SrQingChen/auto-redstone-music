package com.autoredstonemusic.registry;

import java.util.List;

/**
 * 采样表（由构建脚本从素材清单生成，勿手改）：族 → 可用根音（MIDI）。
 * 音名规范：声音注册名 = 族名_<三根音>，如 violin_055；播放音高 = 2^((键-最近根音)/12)。
 */
public final class SampleTable {
    private SampleTable() {}

    public record Family(String name, int[] roots) {}

    public static final List<Family> FAMILIES = List.of(
            new Family("piano", new int[]{21, 24, 27, 30, 33, 36, 39, 42, 45, 48, 51, 54, 57, 60, 63, 66, 69, 72, 75, 78, 81, 84, 87, 90, 93, 96, 99, 102, 105, 108}),
            new Family("violin", new int[]{55, 57, 60, 64, 67, 69, 72, 76, 79, 81, 84, 88, 91, 93, 96}),
            new Family("violin_ens", new int[]{43, 45, 47, 50, 54, 57, 60, 64, 67, 71, 74}),
            new Family("viola", new int[]{36, 38, 40, 43, 47, 50, 53, 57, 60, 64, 67, 71, 74}),
            new Family("cello", new int[]{24, 28, 31, 35, 38, 41, 45, 48, 52, 55, 59, 62, 65}),
            new Family("contrabass", new int[]{16, 18, 22, 24, 26, 28, 30, 32, 37, 40, 44, 47}),
            new Family("trumpet", new int[]{41, 45, 48, 51, 55, 58, 62, 65, 69, 72}),
            new Family("trombone", new int[]{22, 25, 27, 29, 34, 38, 41, 48, 51, 53}),
            new Family("horn", new int[]{21, 24, 27, 31, 34, 38, 41, 45, 48, 62, 65}),
            new Family("tuba", new int[]{17, 22, 27, 29, 34, 38, 41, 46, 50}),
            new Family("flute", new int[]{48, 52, 57, 60, 64, 69, 72, 76, 81, 84}),
            new Family("clarinet", new int[]{38, 41, 46, 50, 53, 58, 62, 65, 70, 74, 78}),
            new Family("oboe", new int[]{46, 50, 53, 58, 62, 65, 70, 74, 77}),
            new Family("bassoon", new int[]{22, 25, 29, 33, 36, 43, 48, 51, 56, 60, 63}),
            new Family("piccolo", new int[]{67, 72, 79, 84, 91}),
            new Family("harp", new int[]{28, 31, 35, 38, 41, 45, 48, 52, 55, 59, 62, 65, 69, 72, 76, 79, 83, 86, 89, 93, 95, 98, 101}),
            new Family("harpsichord", new int[]{22, 24, 26, 28, 30, 32, 34, 36, 38, 40, 42, 44, 46, 48, 50, 52, 54, 56, 58, 60, 62, 64, 66, 68, 70, 72, 74, 76}),
            new Family("organ", new int[]{24, 27, 30, 33, 36, 39, 42, 45, 48, 51, 54, 57, 60, 63, 66, 69, 72, 75, 78, 81, 84}),
            new Family("vibraphone", new int[]{41, 45, 48, 52, 55, 59, 62, 65, 69, 72, 76}),
            new Family("glockenspiel", new int[]{67, 72, 79, 84, 91, 96}),
            new Family("marimba", new int[]{29, 36, 43, 47, 53, 60, 67, 71, 77, 84}),
            new Family("xylophone", new int[]{55, 60, 67, 72, 79, 84, 91, 96}),
            new Family("tubular", new int[]{48, 50, 52, 54, 56, 58, 60, 62, 64}),
            new Family("epiano", new int[]{12, 16, 20, 24, 28, 32, 36, 40, 44, 48, 52, 56, 60, 64, 68, 72, 76, 80, 84, 88, 92, 96}),
            new Family("synth", new int[]{12, 16, 20, 24, 28, 32, 36, 40, 44, 48, 52, 56, 60, 64, 68, 72, 76, 80, 84}),
            new Family("sax", new int[]{32, 34, 36, 38, 40, 42, 44, 46, 48, 50, 52, 54, 56, 58, 60, 62, 64, 66, 68, 70, 72, 74, 76}),
            new Family("recorder", new int[]{60, 62, 64, 66, 68, 70, 72, 74, 76, 78, 82, 84}),
            new Family("drum", new int[]{35, 36, 37, 38, 39, 40, 41, 42, 44, 46, 47, 48, 49, 50, 51, 53, 54, 56, 57, 58, 60, 61, 62, 63, 64, 66, 69, 70, 73, 74, 75, 76, 77, 80, 81}),
            new Family("guitar", new int[]{31, 33, 35, 37, 39, 41, 43, 45, 47, 50, 52, 54, 56, 58, 60, 62, 64, 66, 69, 71, 73, 75, 77, 79, 81, 83})  
    );

    /** 族名 → 序号（编入音高通道）。 */
    public static int familyId(String name) {
        for (int i = 0; i < FAMILIES.size(); i++) {
            if (FAMILIES.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    public static Family byId(int id) {
        return FAMILIES.get(Math.max(0, Math.min(FAMILIES.size() - 1, id)));
    }

    /** 最近根音在 roots 中的下标。 */
    public static int nearestIndex(Family fam, int midiKey) {
        int[] roots = fam.roots();
        int best = 0;
        for (int i = 1; i < roots.length; i++) {
            if (Math.abs(roots[i] - midiKey) < Math.abs(roots[best] - midiKey)) {
                best = i;
            }
        }
        return best;
    }

    public static float pitchFor(Family fam, int midiKey) {
        int root = fam.roots()[nearestIndex(fam, midiKey)];
        return (float) Math.pow(2.0, (midiKey - root) / 12.0);
    }

    // ---- 响度增益（5 位编码，索引 16 = 1.0，步进 9%，覆盖 0.27x..3.6x）----

    private static final int GAIN_CENTER = 16;
    private static final float GAIN_STEP = 1.09f;

    /** 增益倍率 → 5 位索引（0-31）。 */
    public static int quantizeGain(float gain) {
        float clamped = Math.max(0.30f, Math.min(2.2f, gain));
        int idx = GAIN_CENTER + Math.round((float) (Math.log(clamped) / Math.log(GAIN_STEP)));
        return Math.max(0, Math.min(31, idx));
    }

    /** 5 位索引 → 增益倍率。 */
    public static float gainOf(int idx) {
        return (float) Math.pow(GAIN_STEP, Math.max(0, Math.min(31, idx)) - GAIN_CENTER);
    }
}