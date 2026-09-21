package com.zean.filepanel.core;

import java.nio.file.Path;

/**
 * 扫描期间遇到的一个问题（权限不足、路径过长、IO 错误等）。
 *
 * <p>扫描<b>不因单个错误中断</b>：权限不足的目录被跳过并记录，其余部分照常产出。
 * 这样用户至少能看到能扫到的部分，而不是面对一个空白窗口加一句报错。
 *
 * @param path    出问题的路径
 * @param message 简要原因（异常类型 + 消息）
 */
public record ScanError(Path path, String message) {

    public static ScanError of(Path path, Throwable t) {
        String msg = t == null
                ? "未知错误"
                : t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
        return new ScanError(path, msg);
    }

    /** 单行摘要，用于状态栏与日志。 */
    public String summary() {
        return path + " — " + message;
    }
}
