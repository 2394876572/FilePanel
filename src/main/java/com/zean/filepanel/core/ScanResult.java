package com.zean.filepanel.core;

import java.util.List;

/**
 * 一次扫描的完整结果。
 *
 * @param root               扫描根目录
 * @param items              收录到的文件与目录（含目录项，由 UI 决定是否展示）
 * @param excludedDirs       被剪枝的目录列表；用于事后统计“到底隐藏了多少东西”
 * @param excludedFileCount  直接被排除规则挡掉的<b>文件</b>数
 * @param excludedFileBytes  直接被排除规则挡掉的文件总字节数
 * @param errors             记录下来的问题（有上限，见 {@code Scanner#MAX_ERRORS_RECORDED}）
 * @param errorCount         问题总数；可能大于 {@code errors.size()}
 * @param fileCount          收录文件数
 * @param dirCount           收录目录数（不含扫描根）
 * @param totalBytes         收录文件总字节数
 * @param hiddenCount        收录项中被标记为“隐藏”的数量（文件系统属性，与排除规则无关）
 * @param elapsedMillis      扫描耗时
 * @param cancelled          是否被用户取消
 * @param truncated          是否因触达条目上限或深度上限而截断
 */
public record ScanResult(
        java.nio.file.Path root,
        List<FileItem> items,
        List<java.nio.file.Path> excludedDirs,
        long excludedFileCount,
        long excludedFileBytes,
        List<ScanError> errors,
        long errorCount,
        long fileCount,
        long dirCount,
        long totalBytes,
        long hiddenCount,
        long elapsedMillis,
        boolean cancelled,
        boolean truncated) {

    /** 收录条目总数（文件 + 目录）。 */
    public long totalCount() {
        return fileCount + dirCount;
    }

    /** 仅文件部分的条目，供 M1 默认视图（不显示文件夹）使用。 */
    public List<FileItem> filesOnly() {
        return items.stream().filter(i -> !i.directory()).toList();
    }

    public boolean hasErrors() {
        return errorCount > 0;
    }
}
