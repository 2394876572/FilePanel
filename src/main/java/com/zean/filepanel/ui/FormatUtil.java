package com.zean.filepanel.ui;

import java.nio.file.attribute.FileTime;
import java.text.Collator;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Locale;

/**
 * 展示层格式化工具。
 *
 * <p>集中放置的原因：同一份大小/时间信息会同时出现在表格、状态栏、加载页和命令行输出里，
 * 四处各写一遍必然出现“界面显示 85.6 MB、日志显示 85.61 MB”这类不一致。
 */
public final class FormatUtil {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA);

    private static final String[] SIZE_UNITS = {"B", "KB", "MB", "GB", "TB", "PB"};

    /**
     * 中文排序器。
     *
     * <p>用 {@link Collator} 而不是 {@code String.compareTo}：后者按 UTF-16 码元比较，
     * 中文会排成“按 Unicode 码点”的顺序，对中文用户毫无意义；
     * Collator 给出的是拼音顺序，这才是用户期望的“按名称排序”。
     *
     * <p>Collator 非线程安全，故在比较时同步。
     */
    private static final Collator COLLATOR = Collator.getInstance(Locale.CHINA);

    private FormatUtil() {
    }

    /** 人类可读的大小，例如 {@code 1.2 MB}、{@code 812 B}。 */
    public static String size(long bytes) {
        if (bytes < 0) {
            return "—";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < SIZE_UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        if (value >= 100) {
            return String.format(Locale.ROOT, "%.0f %s", value, SIZE_UNITS[unit]);
        }
        if (value >= 10) {
            return String.format(Locale.ROOT, "%.1f %s", value, SIZE_UNITS[unit]);
        }
        return String.format(Locale.ROOT, "%.2f %s", value, SIZE_UNITS[unit]);
    }

    /** {@code 2026-09-20 14:52}；为 null 时返回 {@code —}。 */
    public static String time(FileTime fileTime) {
        if (fileTime == null) {
            return "—";
        }
        Instant instant = fileTime.toInstant();
        return DATE_TIME.format(instant.atZone(ZoneId.systemDefault()));
    }

    /** 千位分隔的整数，例如 {@code 1,245}。 */
    public static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    /**
     * 相对时间，例如 {@code 刚刚}、{@code 5 分钟前}、{@code 3 天前}。
     *
     * <p>超过一周就退回绝对日期：那时候"11 天前"已经不如"2026-09-09"直观。
     *
     * <p>顺带处理了"时间在未来"的情况（系统时钟被调整、或记录来自另一台机器）：
     * 显示"刚刚"而不是负数。
     */
    public static String relativeTime(long epochMillis) {
        if (epochMillis <= 0) {
            return "—";
        }
        long delta = System.currentTimeMillis() - epochMillis;
        if (delta < 0 || delta < 60_000L) {
            return "刚刚";
        }
        if (delta < 3_600_000L) {
            return (delta / 60_000L) + " 分钟前";
        }
        if (delta < 86_400_000L) {
            return (delta / 3_600_000L) + " 小时前";
        }
        if (delta < 7L * 86_400_000L) {
            return (delta / 86_400_000L) + " 天前";
        }
        return DATE_TIME.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }

    /** 毫秒耗时的人类可读形式：小于 1 秒显示毫秒，否则显示秒。 */
    public static String duration(long millis) {
        if (millis < 1000) {
            return millis + " ms";
        }
        return String.format(Locale.ROOT, "%.2f s", millis / 1000.0);
    }

    /** 中文友好的文本比较器。 */
    public static Comparator<String> chineseText() {
        return (a, b) -> {
            String left = a == null ? "" : a;
            String right = b == null ? "" : b;
            synchronized (COLLATOR) {
                return COLLATOR.compare(left, right);
            }
        };
    }

    /**
     * 文件类型对应的标识色。
     *
     * <p>用颜色而不是真实系统图标来区分类型：真实图标要靠 JNA 调 Shell 取 HICON 再转像素，
     * 代价大、还受 DPI 缩放与图标句柄泄漏影响（见计划 §5.9 的取舍）。
     * 一个小色块加上已有的"类型"文字列，已经足够让用户扫一眼就区分图片、文档与压缩包。
     *
     * <p>颜色刻意选了亮度接近的一批：过亮或过暗的颜色在深色主题下会有一半看不清。
     */
    public static String kindColor(com.zean.filepanel.core.FileKind kind) {
        if (kind == null) {
            return "#94a3b8";
        }
        return switch (kind) {
            case DIRECTORY -> "#2563eb";
            case DOCUMENT -> "#3b82f6";
            case SPREADSHEET -> "#16a34a";
            case PRESENTATION -> "#ea580c";
            case IMAGE -> "#8b5cf6";
            case AUDIO -> "#0891b2";
            case VIDEO -> "#db2777";
            case ARCHIVE -> "#ca8a04";
            case CODE -> "#475569";
            case EXECUTABLE -> "#64748b";
            case OTHER -> "#94a3b8";
        };
    }
}
