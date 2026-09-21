package com.zean.filepanel.ops;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 打开与定位。
 *
 * <p>“打开文件”用 {@link Desktop#open}，等价于在资源管理器里双击；
 * “打开所在文件夹”用 {@code explorer /select,} 让资源管理器打开并<b>选中</b>该文件
 * （只打开目录而不选中，用户还得自己再找一遍）。
 *
 * <p>唯一的坑：{@code explorer.exe} 即使成功也返回退出码 1，
 * 所以<b>不能</b>用退出码判断成败，也<b>不能</b> {@code waitFor()} 之后检查返回值。
 */
public final class OpenService {

    private OpenService() {
    }

    /** 当前环境是否支持“打开”。JavaFX 应用正常运行时总是支持的。 */
    public static boolean isOpenSupported() {
        try {
            return Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.OPEN);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 打开文件或其关联程序；目录则用资源管理器打开。
     *
     * @throws IOException 没有关联程序、文件不存在或系统不支持时
     */
    public static void open(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            throw new IOException("文件不存在：" + path);
        }
        if (!isOpenSupported()) {
            throw new IOException("当前环境不支持打开文件");
        }
        try {
            Desktop.getDesktop().open(path.toFile());
        } catch (IOException e) {
            // 最常见的原因是该扩展名没有关联程序，这里补一句可操作的建议
            throw new IOException("无法打开「" + path.getFileName() + "」：" + e.getMessage()
                    + "（可能没有关联的程序）", e);
        } catch (RuntimeException e) {
            throw new IOException("无法打开「" + path.getFileName() + "」：" + e.getMessage(), e);
        }
    }

    /**
     * 在资源管理器中打开所在目录并选中该条目。
     *
     * <p>{@code /select,} 与路径必须作为<b>同一个参数</b>传给 explorer，
     * 拆成两个参数 explorer 会忽略选择动作。
     */
    public static void revealInExplorer(Path path) throws IOException {
        if (path == null) {
            throw new IOException("路径无效");
        }
        Path absolute = path.toAbsolutePath().normalize();
        try {
            // 目录本身没有“选中自己”的必要，直接打开即可
            if (Files.isDirectory(absolute)) {
                new ProcessBuilder("explorer.exe", absolute.toString()).start();
            } else {
                new ProcessBuilder("explorer.exe", "/select," + absolute).start();
            }
        } catch (IOException e) {
            throw new IOException("无法打开资源管理器：" + e.getMessage(), e);
        }
    }

    /** 把路径转成适合复制到剪贴板的文本（不带引号，粘贴到命令行也基本可用）。 */
    public static String pathText(Path path) {
        return path == null ? "" : path.toAbsolutePath().normalize().toString();
    }
}
