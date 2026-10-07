package com.autoredstonemusic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/**
 * 统一日志出口。目标：每次测试都能在 latest.log 里直接看到管线走到哪一步、卡在哪一步。
 *
 * <p><b>开启详尽调试</b>（二选一，文件开关会在每次打开 UI / 启动时重新探测）：
 * <ol>
 *   <li>启动参数：{@code -Darm.debug=true}</li>
 *   <li>在游戏根目录的 midi 文件夹里放一个名为 {@code .debug} 的空文件</li>
 * </ol>
 * 详尽模式下所有 {@link #debug} 以 INFO 级别输出（保证写入 latest.log，而不只进 debug.log），
 * 前缀 [ARM] / [ARM-D] 便于过滤。无论是否详尽模式，错误路径都会输出字节数/魔数/SHA-1 诊断。
 *
 * <p>游戏外（独立自检）无 SLF4J 时自动降级为控制台输出（嵌套类隔离 SLF4J 类型）。
 */
public final class Log {
    private Log() {}

    /** 只在确认 SLF4J 可用后才类加载，隔离运行时依赖。 */
    private static final class Slf4j {
        static void info(String text) {
            Holder.LOGGER.info(text);
        }

        static void error(String text, Throwable t) {
            if (t != null) {
                Holder.LOGGER.error(text, t);
            } else {
                Holder.LOGGER.error(text);
            }
        }

        private static final class Holder {
            static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
        }
    }

    private static final boolean SLF4J = detectSlf4j();

    private static boolean detectSlf4j() {
        try {
            Class.forName("org.slf4j.Logger");
            Class.forName("com.mojang.logging.LogUtils");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean verbose;

    static {
        reinit(null);
    }

    /** 重新探测调试开关（common setup 与每次打开 UI 时调用）。 */
    public static void reinit(Path gameDir) {
        boolean byProperty = Boolean.getBoolean("arm.debug") || Boolean.getBoolean("auto_redstone_music.debug");
        boolean byFile = false;
        try {
            if (gameDir != null) {
                byFile = Files.exists(gameDir.resolve(Config.MIDI_DIR).resolve(".debug"));
            }
        } catch (Exception ignored) {
        }
        boolean old = verbose;
        verbose = byProperty || byFile;
        if (verbose != old) {
            info("详尽调试模式：{}", verbose ? "开启" : "关闭");
        }
    }

    public static boolean verbose() {
        return verbose;
    }

    /** 关键里程碑（始终输出）。 */
    public static void info(String format, Object... args) {
        emit("[ARM] " + format, args, null);
    }

    /** 详尽跟踪（仅调试模式，以 INFO 级别写入 latest.log）。 */
    public static void debug(String format, Object... args) {
        if (verbose) {
            emit("[ARM-D] " + format, args, null);
        }
    }

    public static void warn(String format, Object... args) {
        emit("[ARM] " + format, args, null);
    }

    public static void error(String format, Object... args) {
        emit("[ARM] " + format, args, null);
    }

    public static void error(String message, Throwable t) {
        emit("[ARM] " + message, null, t);
    }

    private static void emit(String line, Object[] args, Throwable t) {
        String text;
        try {
            text = render(line, args);
        } catch (Throwable renderError) {
            // 日志工具绝不允许炸掉调用方（BE tick 中抛异常 = 服务器崩溃）
            text = line + " / args=" + java.util.Arrays.toString(args) + " / renderError=" + renderError;
        }
        if (SLF4J) {
            if (t != null) {
                Slf4j.error(text, t);
            } else {
                Slf4j.info(text);
            }
        } else {
            System.out.println(text);
            if (t != null) {
                t.printStackTrace(System.out);
            }
        }
    }

    /**
     * 手动替换 "{}" 占位符，不经过 java.util.Formatter：
     * 字面量 '%'（如 "进度 100%"）不会触发 UnknownFormatConversionException。
     */
    private static String render(String line, Object[] args) {
        if (args == null || args.length == 0) {
            return line;
        }
        StringBuilder sb = new StringBuilder(line.length() + 32);
        int argIndex = 0;
        int i = 0;
        while (i < line.length()) {
            if (i + 1 < line.length() && line.charAt(i) == '{' && line.charAt(i + 1) == '}' && argIndex < args.length) {
                sb.append(args[argIndex++]);
                i += 2;
            } else {
                sb.append(line.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }

    /** 字节块诊断：长度 + 头部魔数十六进制 + SHA-1，一眼判断数据是否在途中被换掉/截断。 */
    public static String bytesDiag(byte[] bytes) {
        if (bytes == null) {
            return "null";
        }
        StringBuilder head = new StringBuilder();
        for (int i = 0; i < Math.min(8, bytes.length); i++) {
            head.append("%02x".formatted(bytes[i]));
        }
        return "len=%d head=%s sha1=%s".formatted(bytes.length, head, sha1(bytes));
    }

    public static String sha1(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append("%02x".formatted(b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "sha1-error";
        }
    }
}
