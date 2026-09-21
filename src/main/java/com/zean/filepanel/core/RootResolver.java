package com.zean.filepanel.core;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;

/**
 * 扫描根目录解析。
 *
 * <p>产品定位是“软件放进哪个文件夹就管哪个文件夹”，因此默认以<b>启动器所在位置</b>推导根目录，
 * 而不是让用户每次手选。优先级（已确认的设计 D1/§4.2）：
 * <ol>
 *   <li>命令行 {@code --root}</li>
 *   <li>上次使用的根目录（配置记忆，M4 接入）</li>
 *   <li>启动器所在目录的<b>父目录</b>——规范部署形态是 {@code <根>\FilePanel\FilePanel.exe}</li>
 *   <li>启动器所在目录（jar/批处理直接放在目标文件夹里的情况）</li>
 *   <li>当前工作目录 → 用户主目录（兜底，保证永远有一个可用值）</li>
 * </ol>
 * 每一步都要求「存在 + 是目录 + 可读」，否则继续往下走，绝不返回一个不可用的根。
 */
public final class RootResolver {

    /** 规范部署形态下启动器所在的子目录名，命中则取上级目录作为根。 */
    private static final String LAUNCHER_SUBDIR = "FilePanel";

    /**
     * 安装版的开关：由打包时注入的 {@code -Dfilepanel.installed=true} 带上。
     *
     * <p>**为什么需要一个显式标记，而不是"看目录像不像安装位置"**：
     * 绿色版的根目录是"启动器所在目录"，而安装版的启动器在
     * {@code %LOCALAPPDATA%\Programs\FilePanel\}，管它毫无意义。
     * 两者的区别只有打包方式知道，靠路径去猜（例如"是否在 Programs 下"）会在
     * 用户把绿色版解压到类似路径时误判。所以由打包脚本显式写进启动配置，
     * 运行期只读这个标记。
     */
    public static final String INSTALLED_PROPERTY = "filepanel.installed";

    /** 是否运行在安装版形态下。 */
    public static boolean isInstalledMode() {
        return Boolean.parseBoolean(System.getProperty(INSTALLED_PROPERTY, "false"));
    }

    /**
     * 是否需要先问用户"要管理哪个文件夹"。
     *
     * <p>只有安装版会遇到这个问题：它的启动器所在目录是安装目录，不能拿来当根。
     * 绿色版永远不需要（启动器在哪就管哪）。抽成纯函数是为了能被单测覆盖——
     * 弹窗本身没法自动化断言，"什么时候该弹"可以。
     *
     * @param explicitFromCli 命令行是否给了 {@code --root}
     * @param remembered      是否记住了上次的根目录（且该目录仍然可用）
     * @param installed       是否安装版
     */
    public static boolean requiresFolderChoice(boolean explicitFromCli, boolean remembered,
                                              boolean installed) {
        return installed && !explicitFromCli && !remembered;
    }

    /**
     * @param root   最终采用的根目录（绝对规范路径）
     * @param reason 采用原因，用于界面与日志展示，让用户明白“为什么扫的是这里”
     */
    public record Resolved(Path root, String reason) {
    }

    private RootResolver() {
    }

    public static Resolved resolve(Path explicitFromCli, Path remembered) {
        Resolved r = accept(explicitFromCli, "命令行 --root 指定");
        if (r != null) {
            return r;
        }
        r = accept(remembered, "上次打开的文件夹");
        if (r != null) {
            return r;
        }

        Path launcherDir = launcherDirectory();
        if (launcherDir != null) {
            if (isLauncherSubdir(launcherDir)) {
                r = accept(launcherDir.getParent(), "启动器位于 " + LAUNCHER_SUBDIR + " 子目录，取上级目录");
                if (r != null) {
                    return r;
                }
            }
            r = accept(launcherDir, "启动器所在目录");
            if (r != null) {
                return r;
            }
        }

        r = accept(Path.of(System.getProperty("user.dir", ".")), "当前工作目录（兜底）");
        if (r != null) {
            return r;
        }

        Path home = Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
        return new Resolved(home, "用户主目录（最终兜底）");
    }

    private static boolean isLauncherSubdir(Path dir) {
        Path name = dir.getFileName();
        return name != null && LAUNCHER_SUBDIR.equalsIgnoreCase(name.toString());
    }

    private static Resolved accept(Path candidate, String reason) {
        if (candidate == null) {
            return null;
        }
        try {
            Path abs = candidate.toAbsolutePath().normalize();
            if (Files.isDirectory(abs) && Files.isReadable(abs)) {
                return new Resolved(abs, reason);
            }
        } catch (RuntimeException ignored) {
            // 非法路径（空串、含非法字符等）直接视为不可用
        }
        return null;
    }

    /**
     * 推导启动器所在目录。
     *
     * <p>两条来源：
     * <ul>
     *   <li>{@code jpackage.app-path}：jpackage 生成的 exe 启动器会设置该属性，指向 exe 本身，
     *       因此取其父目录。这是免装 Java 成品的主要路径。</li>
     *   <li>代码来源位置（{@code CodeSource}）：直接用 jar 或 classes 目录启动时使用。</li>
     * </ul>
     *
     * <p>开发期（{@code mvn javafx:run}）代码来源是 {@code target\classes}，指向它毫无意义，
     * 因此显式识别并放弃，交由后续的“当前工作目录”兜底。
     */
    private static Path launcherDirectory() {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) {
            try {
                Path exe = Path.of(appPath).toAbsolutePath().normalize();
                Path parent = exe.getParent();
                if (parent != null) {
                    return parent;
                }
            } catch (RuntimeException ignored) {
                // 落到 CodeSource
            }
        }

        try {
            CodeSource cs = RootResolver.class.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                return null;
            }
            Path location = Path.of(cs.getLocation().toURI());
            Path dir = Files.isDirectory(location) ? location : location.getParent();
            if (dir == null) {
                return null;
            }
            dir = dir.toAbsolutePath().normalize();
            return isMavenDevOutput(dir) ? null : dir;
        } catch (URISyntaxException | RuntimeException ignored) {
            return null;
        }
    }

    /**
     * 识别 {@code .../target/classes} 这类 Maven 开发期产物目录。
     *
     * <p>同时覆盖 {@code target/test-classes}，保证在测试 JVM 里推导结果也符合预期。
     */
    static boolean isMavenDevOutput(Path dir) {
        Path name = dir.getFileName();
        Path parent = dir.getParent();
        if (name == null || parent == null) {
            return false;
        }
        Path parentName = parent.getFileName();
        return parentName != null
                && "target".equalsIgnoreCase(parentName.toString())
                && name.toString().endsWith("classes");
    }
}
