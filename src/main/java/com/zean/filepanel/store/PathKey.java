package com.zean.filepanel.store;

import java.nio.file.Path;
import java.util.Locale;

/**
 * 路径作为键时的归一化。
 *
 * <p>为什么需要它：Windows 的文件系统<b>忽略大小写</b>，{@code C:\a\B.TXT} 与 {@code C:\A\b.txt}
 * 是同一个文件。用户给其中一个打了标签，切换到另一个大小写形式时若不归一化，
 * 标签就会"消失"——这类问题不会报错，只会让用户觉得数据莫名丢了。
 *
 * <p>因此：<b>存进文件时保留用户看到的原始路径</b>（便于阅读与排查），
 * <b>内存里查表时用小写归一化的键</b>（保证命中）。
 */
public final class PathKey {

    private static final boolean CASE_INSENSITIVE =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private PathKey() {
    }

    /** 归一化后的查表键。 */
    public static String of(Path path) {
        if (path == null) {
            return "";
        }
        String absolute = path.toAbsolutePath().normalize().toString();
        return CASE_INSENSITIVE ? absolute.toLowerCase(Locale.ROOT) : absolute;
    }

    /** 两个路径是否指向同一个文件（按当前平台的比较规则）。 */
    public static boolean same(Path a, Path b) {
        return !of(a).isEmpty() && of(a).equals(of(b));
    }

    /** 当前平台是否忽略路径大小写——供自检与测试断言用。 */
    public static boolean caseInsensitive() {
        return CASE_INSENSITIVE;
    }
}
