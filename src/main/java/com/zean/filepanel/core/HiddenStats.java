package com.zean.filepanel.core;

/**
 * 被排除内容的规模统计。
 *
 * <p>为什么需要单独一趟统计：排除规则是<b>目录级剪枝</b>的，剪掉 {@code .venv} 时我们
 * 根本不会进入它内部，因此扫描本身不知道里面有多少东西。而用户最需要的恰恰是
 * 「我的文件为什么变少了、被藏了多少」——所以扫描完成后另起一趟轻量遍历，
 * 只读属性求大小，不构造对象。
 *
 * <p>这一趟在表格已经显示<b>之后</b>才跑，因此不拖慢首屏。
 *
 * @param fileCount 隐藏的文件数
 * @param dirCount  隐藏的目录数
 * @param bytes     隐藏文件的总字节数
 * @param truncated 是否因触达上限而提前停止统计（此时数值是下界）
 */
public record HiddenStats(long fileCount, long dirCount, long bytes, boolean truncated) {

    public static HiddenStats empty() {
        return new HiddenStats(0, 0, 0, false);
    }

    public boolean isEmpty() {
        return fileCount == 0 && dirCount == 0;
    }
}
