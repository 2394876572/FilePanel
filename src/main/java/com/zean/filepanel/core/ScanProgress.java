package com.zean.filepanel.core;

/**
 * 扫描进度快照。
 *
 * <p>扫描线程按“每 100ms 或每 250 个文件”节流后推送，避免高频跨线程回调把 UI 线程压垮。
 * 加载页据此显示<b>真实</b>进度，而不是无限循环的假动画。
 *
 * @param files      已收录的文件数（不含被排除的）
 * @param dirs       已收录的目录数（不含扫描根，不含被排除的）
 * @param bytes      已收录文件的总字节数
 * @param excluded   已跳过的排除项数（目录按 1 计，不含其内部条目）
 * @param currentPath 当前正在处理的位置，用于让用户看到“在扫哪里”
 */
public record ScanProgress(
        long files,
        long dirs,
        long bytes,
        long excluded,
        String currentPath) {

    public static ScanProgress zero() {
        return new ScanProgress(0, 0, 0, 0, "");
    }

    /** 已发现的条目总数（文件 + 目录）。 */
    public long total() {
        return files + dirs;
    }
}
