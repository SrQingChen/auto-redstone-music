package com.autoredstonemusic;

/**
 * 全局常量。v1 不做配置文件，所有可调参数集中在这里，后续再迁到 ModConfigSpec。
 *
 * <p>时间体系（最重要）：全曲量化到 0.1 秒栅格 = 2 游戏刻（{@link #TICKS_PER_SLOT}）。
 * 纯红石引擎下，波前由中继器链驱动：每个中继器 DELAY 档 = DELAY*2 游戏刻，
 * 链上累积延迟必须精确等于 0.1s*槽位，ChainCompiler 会在编译后自检。
 */
public final class Config {
    private Config() {}

    /** 每秒游戏刻数（时间体系的最小单位 = 1 刻 = 50ms）。 */
    public static final int TICKS_PER_SECOND = 20;

    /** 精密中继器单颗最大延迟（游戏刻）。更长的间隔由多颗串联表达。 */
    public static final int REPEATER_MAX_DELAY_TICKS = 200;

    /** 播放自然结束后再多走的刻数（尾音缓冲，5 秒）。 */
    public static final int TAIL_TICKS = 100;

    /** 乐曲最大刻数（8192 刻 ≈ 6.8 分钟）。 */
    public static final int MAX_TICKS = 8192;


    /** 轨道线数量上限 = 布局位置表容量（玩家层 4+4、下一层 4+4、正下方 1）。 */
    public static final int MAX_LINES = 17;

    /** 每条线每个时间槽的复音容量（左右枝路各 1；红石线无法激活上方音符盒，无上方位）。 */
    public static final int SIDE_CAPACITY = 2;

    /** 构建阶段每刻的时间片预算（纳秒）。到时即停，剩余工作交给后续刻。 */
    public static final long BUILD_TIME_BUDGET_NANOS = 8_000_000L;

    /** 构建阶段每刻写入数的硬上限（防止极端便宜写入刷爆 tick）。 */
    public static final int BUILD_MAX_WRITES_PER_TICK = 20_000;

    /** 兼容旧引用：每刻最少保底处理量（时间片用尽前也至少处理这么多）。 */
    public static final int BUILD_BATCH = 512;

    /** 歌曲上传分包大小（字节）。 */
    public static final int UPLOAD_CHUNK_SIZE = 28_000;

    /** 游戏根目录下 MIDI 文件夹名。 */
    public static final String MIDI_DIR = "midi";

    /** 链在轴向上的起始偏移：z=0 是控制器，z=1 是启动源（红石块）槽位。 */
    public static final int CHAIN_START_OFFSET = 1;

    /** 实时录制模式捕获的系统声音设备名（ffmpeg dshow）。空 = 不录声音。
     *  用 ffmpeg -list_devices true -f dshow -i dummy 查询；常见如 "立体声混音 (Realtek...)"。 */
    public static final String AUDIO_CAPTURE_DEVICE = "";

    /** 清空立方体：纬向半宽（满轨道 ±20 + 走廊 2 + 外扩 2）。 */
    public static final int CLEAR_LATERAL_HALF = 24;

    /** 清空立方体：垂直下界（下层基座 -4 再外扩 2）。 */
    public static final int CLEAR_DY_MIN = -6;

    /** 清空立方体：垂直上界（上层红石灯 +1 再外扩 2）。 */
    public static final int CLEAR_DY_MAX = 3;

    /** 清空立方体：轴向两端各外扩格数。 */
    public static final int CLEAR_AXIAL_MARGIN = 2;

    /** 前导刻数：确保首个音符的枝路延迟 ≥1 刻（+50ms，不可感知）。 */
    public static final int LEADING_TICKS = 1;
}
