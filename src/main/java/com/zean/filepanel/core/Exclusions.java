package com.zean.filepanel.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 排除规则：决定哪些目录不进入、哪些文件不进列表。
 *
 * <p><b>为什么这是本产品的第一优先级功能</b>：实测目标目录 1245 个文件里有 1149 个来自
 * {@code 某项目\collector\.venv}（含 497 个 {@code .pyc}）。没有排除规则，界面会被
 * 依赖包的源码淹没，用户根本找不到自己的文件。排除在<b>目录级剪枝</b>，
 * 因此省下的是整棵子树的遍历成本，而不是过滤成本。
 *
 * <p>匹配一律<b>忽略大小写</b>、只针对<b>条目名</b>（不含路径）。
 * 这样 {@code .venv} 出现在任何层级都能命中，无需为每个根目录各配一条。
 *
 * <p>不可变类：扫描线程读取、设置界面在 M2 生成新实例替换，避免并发修改。
 */
public final class Exclusions {

    /** 默认排除的目录名（精确匹配，忽略大小写）。 */
    private static final List<String> DEFAULT_DIR_NAMES = List.of(
            // 依赖与虚拟环境
            ".venv", "venv", "env", "__pycache__", "node_modules", ".pnpm-store", "bower_components",
            // 版本控制
            ".git", ".svn", ".hg", ".bzr",
            // 构建产物与工程目录
            "target", "build", "dist", "out", "obj", "bin", ".gradle", ".mvn", ".idea", ".vscode",
            // 缓存
            ".cache", ".m2", ".npm", ".nuget",
            // 本工具自身（程序目录 + 便携数据目录，避免“扫描到自己”）
            ".filepanel", "FilePanel", "FilePanel-Src",
            // 系统
            "$RECYCLE.BIN", "System Volume Information", "Recovery");

    /** 默认排除的目录名通配符。 */
    private static final List<String> DEFAULT_DIR_PATTERNS = List.of(
            "*.egg-info", ".tox", "*.dist-info");

    /** 默认排除的文件名通配符。 */
    private static final List<String> DEFAULT_FILE_PATTERNS = List.of(
            "~$*",          // Office 临时/锁文件（用户目录里实测有 8 个）
            "*.tmp", "*.temp", "*.swp", "*.swo", "*.bak", "*.orig", "*.rej",
            "*.pyc", "*.pyo", "*.class", "*.pdb",
            "Thumbs.db", "ehthumbs.db", "desktop.ini", ".DS_Store", "$RECYCLE.BIN");

    private final boolean enabled;
    private final Set<String> dirNames;
    private final List<Pattern> dirPatterns;
    private final List<Pattern> filePatterns;

    /**
     * 程序自身的目录（绝对路径）。
     *
     * <p>为什么不能只靠名字排除：把程序平铺到顶层部署时，程序自己的目录就叫
     * {@code app} 与 {@code runtime}——这两个名字太通用，若按名字排除，
     * 用户自己在别处建的 {@code app} 目录也会被无端藏起来。
     * 所以这里记的是<b>精确路径</b>，只藏程序自己的那两份。
     */
    private final Set<Path> selfPaths;

    private Exclusions(boolean enabled, Set<String> dirNames,
                       List<Pattern> dirPatterns, List<Pattern> filePatterns,
                       Set<Path> selfPaths) {
        this.enabled = enabled;
        this.dirNames = Set.copyOf(dirNames);
        this.dirPatterns = List.copyOf(dirPatterns);
        this.filePatterns = List.copyOf(filePatterns);
        this.selfPaths = Set.copyOf(selfPaths);
    }

    /** 默认规则（启用），并自动带上"程序自身目录"的精确排除。 */
    public static Exclusions defaults() {
        return new Exclusions(true, buildDirNames(), buildDirPatterns(), buildFilePatterns(),
                detectSelfPaths());
    }

    /** 空规则（不排除任何东西），用于“显示全部文件”与对照测试。 */
    public static Exclusions none() {
        return new Exclusions(false, Set.of(), List.of(), List.of(), Set.of());
    }

    /**
     * 默认规则 + 按<b>扫描根目录</b>探测到的程序自身路径。
     *
     * <h2>为什么需要它（这是一个被真实数据抓出来的缺口）</h2>
     * {@link #detectSelfPaths()} 只认 {@code jpackage.app-path}，而那个属性<b>只有打包后的 exe
     * 启动时才有</b>。用 {@code scripts\_java.cmd} 从 {@code target\classes} 启动时它为空，
     * 于是同一份代码在两种启动方式下扫出两个不同的数字：开发期把程序自己的
     * {@code app\}（12 个 jar）与 {@code runtime\}（142 个文件）都算成了"用户的文件"，
     * 报 197 个；双击 exe 时报 114 个。
     *
     * <p>后果不只是数字难看：{@code --content-search} 会去索引 {@code runtime\legal\*.md}
     * 这类 Java 许可说明，把"程序家具"灌进全文索引，用户搜索时会搜到一堆无关内容。
     * 而"命令行验证出来的数字"与"用户看到的数字"对不上，验证本身就失去意义。
     *
     * <p>判定方式：根目录里同时存在 {@code FilePanel.exe}、{@code app\} 与 {@code runtime\}
     * 就认定这是一份平铺部署，把后两者按精确路径排除。三者缺一就不动——
     * 宁可少排除，也不能因为用户恰好建了个叫 {@code app} 的目录就把他的文件藏起来。
     *
     * @param root 扫描根目录；为 null 时等价于 {@link #defaults()}
     */
    public static Exclusions forRoot(Path root) {
        Exclusions base = defaults();
        Path deployment = detectDeploymentDir(root);
        if (deployment == null) {
            return base;
        }
        Set<Path> paths = new LinkedHashSet<>(base.selfPaths);
        paths.add(deployment.resolve("app").normalize());
        paths.add(deployment.resolve("runtime").normalize());
        paths.add(deployment.resolve("FilePanel.ico").normalize());
        return new Exclusions(base.enabled, base.dirNames, base.dirPatterns, base.filePatterns, paths);
    }

    /**
     * 根目录是否就是一份平铺部署的程序目录。
     *
     * @return 部署目录（即 {@code root} 自身）；不是部署形态时返回 null
     */
    static Path detectDeploymentDir(Path root) {
        if (root == null) {
            return null;
        }
        try {
            Path dir = root.toAbsolutePath().normalize();
            if (Files.isRegularFile(dir.resolve("FilePanel.exe"))
                    && Files.isDirectory(dir.resolve("app"))
                    && Files.isDirectory(dir.resolve("runtime"))) {
                return dir;
            }
        } catch (RuntimeException e) {
            // 路径非法（过长、含非法字符）时保守返回 null，不影响扫描
            return null;
        }
        return null;
    }

    /**
     * 从 jpackage 启动器位置推导程序自身的目录。
     *
     * <p>{@code jpackage.app-path} 只在打包产物里存在；开发期运行返回空集合，
     * 那时程序自己的 {@code target} 目录已经由名字规则排除了。
     */
    public static Set<Path> detectSelfPaths() {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath == null || appPath.isBlank()) {
            return Set.of();
        }
        try {
            Path exe = Path.of(appPath).toAbsolutePath().normalize();
            Path dir = exe.getParent();
            if (dir == null) {
                return Set.of();
            }
            Set<Path> paths = new LinkedHashSet<>();
            // jpackage app-image 固定用这两个名字装"程序内部文件"与"自带运行时"
            paths.add(dir.resolve("app").normalize());
            paths.add(dir.resolve("runtime").normalize());
            // 图标文件是程序家具，不是用户内容；exe 本身留着——那是用户要双击的入口
            paths.add(dir.resolve("FilePanel.ico").normalize());
            return paths;
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    /** 从默认规则派生，仅切换启用开关。 */
    public Exclusions withEnabled(boolean enabled) {
        if (enabled == this.enabled) {
            return this;
        }
        return new Exclusions(enabled, dirNames, dirPatterns, filePatterns, selfPaths);
    }

    /** 供测试使用：指定程序自身目录。 */
    public Exclusions withSelfPaths(Set<Path> paths) {
        return new Exclusions(enabled, dirNames, dirPatterns, filePatterns, paths);
    }

    /** 程序自身目录（只读），供自检输出。 */
    public Set<Path> selfPaths() {
        return selfPaths;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int dirRuleCount() {
        return dirNames.size() + dirPatterns.size();
    }

    public int fileRuleCount() {
        return filePatterns.size();
    }

    /** 供设置界面与自检输出展示的规则概况。 */
    public String describe() {
        if (!enabled) {
            return "已关闭（显示全部文件）";
        }
        return dirRuleCount() + " 条目录规则 / " + fileRuleCount() + " 条文件规则";
    }

    /**
     * 该目录是否应被跳过（连同整棵子树）。
     *
     * <p><b>调用方必须自行排除扫描根目录本身</b>：若根目录恰好叫 {@code build}，
     * 排除它会让扫描结果直接为空。
     */
    public boolean excludesDirectory(Path dir) {
        if (!enabled || dir == null) {
            return false;
        }
        // 程序自身的目录按精确路径排除，避免 app / runtime 这类通用名字误伤用户的目录
        if (isSelfPath(dir)) {
            return true;
        }
        Path fileName = dir.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString();
        if (dirNames.contains(name.toLowerCase(Locale.ROOT))) {
            return true;
        }
        for (Pattern p : dirPatterns) {
            if (p.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }

    /** 是否为程序自身的目录或家具文件（按精确路径比对）。 */
    public boolean isSelfPath(Path path) {
        if (selfPaths.isEmpty() || path == null) {
            return false;
        }
        try {
            return selfPaths.contains(path.toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 该文件是否不进列表（其大小仍会被计入"已隐藏"统计）。 */
    public boolean excludesFile(Path file) {
        if (!enabled || file == null) {
            return false;
        }
        // 程序家具（FilePanel.ico）与自带运行时里的文件都按精确路径排除
        if (isSelfPath(file)) {
            return true;
        }
        Path fileName = file.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString();
        for (Pattern p : filePatterns) {
            if (p.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> buildDirNames() {
        Set<String> s = new LinkedHashSet<>();
        for (String n : DEFAULT_DIR_NAMES) {
            s.add(n.toLowerCase(Locale.ROOT));
        }
        return s;
    }

    private static List<Pattern> buildDirPatterns() {
        return compile(DEFAULT_DIR_PATTERNS);
    }

    private static List<Pattern> buildFilePatterns() {
        return compile(DEFAULT_FILE_PATTERNS);
    }

    private static List<Pattern> compile(List<String> globs) {
        List<Pattern> out = new ArrayList<>(globs.size());
        for (String g : globs) {
            out.add(Glob.toPattern(g));
        }
        return out;
    }

    /** 供测试与自检读取的目录名规则（只读）。返回<b>本实例</b>实际生效的那一份。 */
    public Set<String> dirNames() {
        return dirNames;
    }

    /**
     * 本实例实际生效的目录通配符规则（已编译）。
     *
     * <p>这里曾经返回的是静态常量 {@code DEFAULT_FILE_PATTERNS}，而 {@link #dirNames()}
     * 返回的是实例字段——两个"读取本实例规则"的访问器口径不一致。
     * 更要紧的是它<b>在说谎</b>：一旦将来文件规则变得可配置（界面上可增删），
     * 这个访问器仍会返回默认常量，而调用方（测试、自检、设置界面）会据此显示错误的规则。
     * 现在它返回实例字段，与 {@link #dirNames()} 口径一致。
     */
    public List<Pattern> compiledFilePatterns() {
        return filePatterns;
    }

    /** 本实例实际生效的目录通配符规则（已编译），与 {@link #compiledFilePatterns()} 对称。 */
    public List<Pattern> compiledDirPatterns() {
        return dirPatterns;
    }
}
