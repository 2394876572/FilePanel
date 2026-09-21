package com.zean.filepanel.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 删除策略（设计决策 D4）。
 *
 * <p>核心规则：<b>按大小分流</b>。阈值默认 1 GiB，可配置。
 * <ul>
 *   <li>小于阈值 → 放入回收站（可还原）</li>
 *   <li>不小于阈值 → 直接永久删除（需二次确认）</li>
 * </ul>
 * 为什么这样分：超大文件放进回收站会挤占卷配额甚至失败，
 * Windows 自身对超过回收站配额的文件也会退化为永久删除。所以对超大文件走永久删除符合系统惯例；
 * 而小文件误删代价高、恢复成本低，默认保护。
 *
 * <h2>为什么把这一层做成纯逻辑</h2>
 * 这是整个 M3 里最容易出错、后果最严重（误删且不可恢复）的规则。
 * 把它做成不依赖文件系统、不依赖 JNA 的纯函数，就可以用穷举的组合测试覆盖，
 * 而不是只能靠在真实磁盘上试删来验证。
 *
 * <h2>目录怎么处理（明确的取舍）</h2>
 * {@link FileItem#size()} 对目录恒为 0（扫描期不递归求和，否则每次扫描都要走一遍全树）。
 * 因此目录<b>一律先尝试回收站</b>，与资源管理器行为一致；
 * 若因体积过大被 Shell 拒绝，再由界面提示是否改为永久删除。
 * 这样避免了"为了判断一个目录是否超过 1GB 而先遍历它"的开销。
 */
public final class DeletePolicy {

    public static final long GIB = 1024L * 1024 * 1024;

    /** 默认永久删除阈值：1 GiB。 */
    public static final long DEFAULT_THRESHOLD_BYTES = GIB;

    /**
     * 阈值下限：1 MiB。
     *
     * <p>存在的理由是<b>防御损坏的配置</b>：如果配置文件被改成 0 或负数，
     * 而阈值又被解释成字节数，那么几乎所有文件都会被判为"永久删除"，
     * 一次误点就会造成不可恢复的数据丢失。下限把这种最坏情况挡在门外。
     */
    public static final long MIN_THRESHOLD_BYTES = 1024L * 1024;

    /** 设置界面提供的预设阈值。 */
    public static final List<Long> PRESET_THRESHOLDS =
            List.of(512L * 1024 * 1024, GIB, 2 * GIB, 5 * GIB, 10 * GIB);

    /** 单个条目的删除方式。 */
    public enum Action {
        /** 放入回收站，可从回收站还原。 */
        RECYCLE,
        /** 永久删除，不可恢复。 */
        PERMANENT
    }

    private final long thresholdBytes;
    private final boolean alwaysRecycle;

    public DeletePolicy() {
        this(DEFAULT_THRESHOLD_BYTES, false);
    }

    public DeletePolicy(long thresholdBytes, boolean alwaysRecycle) {
        this.thresholdBytes = clampThreshold(thresholdBytes);
        this.alwaysRecycle = alwaysRecycle;
    }

    /**
     * 把任意输入夹到合法范围。
     *
     * <p>单独抽出来是为了让"界面显示的值"与"实际生效的值"必然一致：
     * 界面上如果把 0 显示成"0 B"而策略内部悄悄变成 1 MiB，用户就无法理解为什么
     * 一个 1 KB 的文件没有被永久删除。设置界面直接用这个方法回显。
     */
    public static long clampThreshold(long bytes) {
        return Math.max(MIN_THRESHOLD_BYTES, bytes);
    }

    /** 输入是否会被下限抬高（设置界面据此给出提示，而不是静默改值）。 */
    public static boolean isBelowMinimum(long bytes) {
        return bytes < MIN_THRESHOLD_BYTES;
    }

    public long thresholdBytes() {
        return thresholdBytes;
    }

    /** 是否启用了“所有删除一律走回收站”（给保守用户的退路）。 */
    public boolean alwaysRecycle() {
        return alwaysRecycle;
    }

    public DeletePolicy withThreshold(long bytes) {
        return new DeletePolicy(bytes, alwaysRecycle);
    }

    public DeletePolicy withAlwaysRecycle(boolean value) {
        return new DeletePolicy(thresholdBytes, value);
    }

    /** 单个条目的删除方式。 */
    public Action decide(FileItem item) {
        if (item == null) {
            return Action.RECYCLE;
        }
        if (alwaysRecycle) {
            return Action.RECYCLE;
        }
        // 网络路径通常没有回收站，直接按永久删除流程走（界面会单独提示）
        if (isNetworkPath(item.path())) {
            return Action.PERMANENT;
        }
        // 目录大小未知，先尝试回收站；失败后由界面提示改为永久删除
        if (item.directory()) {
            return Action.RECYCLE;
        }
        return item.size() >= thresholdBytes ? Action.PERMANENT : Action.RECYCLE;
    }

    /**
     * 对一批选中项给出完整方案。
     *
     * <p>界面必须用这个方案来决定弹哪种确认框：
     * 全是可回收项时只需普通确认；一旦含永久删除项就必须红色警示；
     * 两者混合时还必须显式告知"其中 N 个将被永久删除"，绝不能静默把大文件永久删掉。
     */
    public DeletePlan plan(List<FileItem> items) {
        List<FileItem> recyclable = new ArrayList<>();
        List<FileItem> permanent = new ArrayList<>();
        if (items != null) {
            for (FileItem item : items) {
                if (item == null) {
                    continue;
                }
                if (decide(item) == Action.RECYCLE) {
                    recyclable.add(item);
                } else {
                    permanent.add(item);
                }
            }
        }
        return new DeletePlan(List.copyOf(recyclable), List.copyOf(permanent));
    }

    /**
     * 是否为网络路径（UNC）。
     *
     * <p>只识别 {@code \\server\share} 形式。映射到盘符的网络驱动器无法在此判断，
     * 但若回收站不可用，Shell 会失败，界面按“回收站失败 → 询问是否永久删除”的流程处理，
     * 因此不会出现静默永久删除。
     */
    public static boolean isNetworkPath(Path path) {
        if (path == null) {
            return false;
        }
        String s = path.toString();
        return s.startsWith("\\\\") || s.startsWith("//");
    }

    /** 阈值的人类可读描述，例如 {@code 1.00 GB}。 */
    public String describeThreshold() {
        return humanSize(thresholdBytes);
    }

    /** 供测试与自检读取的当前规则描述。 */
    public String describe() {
        if (alwaysRecycle) {
            return "所有删除一律放入回收站（阈值已忽略）";
        }
        return "小于 " + describeThreshold() + " 放入回收站，不小于则永久删除";
    }

    /** 字节数的人类可读描述，例如 {@code 1.00 GB}。设置界面也用它，保证两处显示一致。 */
    public static String humanSize(long bytes) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double v = bytes;
        int u = 0;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        if (u == 0) {
            return bytes + " B";
        }
        return String.format(Locale.ROOT, "%.2f %s", v, units[u]);
    }
}
