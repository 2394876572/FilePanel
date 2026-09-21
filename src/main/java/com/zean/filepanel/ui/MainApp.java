package com.zean.filepanel.ui;

import com.zean.filepanel.core.CliOptions;
import com.zean.filepanel.core.DeletePolicy;
import com.zean.filepanel.core.Exclusions;
import com.zean.filepanel.core.FileItem;
import com.zean.filepanel.core.FileKind;
import com.zean.filepanel.core.Glob;
import com.zean.filepanel.core.HiddenStats;
import com.zean.filepanel.core.RootResolver;
import com.zean.filepanel.core.ScanError;
import com.zean.filepanel.core.ScanResult;
import com.zean.filepanel.core.Scanner;
import com.zean.filepanel.core.SearchParser;
import com.zean.filepanel.core.SearchQuery;
import com.zean.filepanel.ops.ContentExtractor;
import com.zean.filepanel.ops.ContentIndexer;
import com.zean.filepanel.ops.DeleteService;
import com.zean.filepanel.store.ConfigStore;
import com.zean.filepanel.store.MachineState;
import com.zean.filepanel.store.ContentIndex;
import com.zean.filepanel.store.DeleteJournal;
import com.zean.filepanel.store.UiState;
import com.zean.filepanel.win.Shell32;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 应用入口（JavaFX Application）。
 *
 * <p>三种运行模式：
 * <ul>
 *   <li>默认 —— 打开主窗口，走「解析根目录 → 扫描 → 加载页 → 表格」链路</li>
 *   <li>{@code --scan} —— 无界面扫描并输出统计。存在的意义是让扫描结果<b>可被断言</b>：
 *       “排除规则是否真的把 1245 变成 170”这种结论必须能从命令行验证，而不是靠肉眼看界面</li>
 *   <li>{@code --selftest} —— 启动环境自检。M6 会用它在<b>没有装 Java 的机器</b>上
 *       证明免装分发成立</li>
 *   <li>{@code --content-search} —— 无界面建立内容索引并输出命中文件与摘要。
 *       存在的意义同样是<b>可断言</b>：全文搜索最容易出的问题是"以为搜到了"，
 *       把它放到命令行才能拿真实文档验证抽取链路（ZIP 内部条目名、编码、截断）</li>
 * </ul>
 */
public class MainApp extends Application {

    public static final String APP_NAME = "文件面板";
    public static final String APP_VERSION = "1.0.0-SNAPSHOT";
    /** 当前所处的里程碑，随开发推进更新，便于自检输出定位构建版本 */
    public static final String BUILD_STAGE = "M8 缩略图与系统图标 + M10 搜索完善与批量创建";

    private static final String CSS_PATH = "/css/app.css";

    /** 从 jar 文件名中提取版本号，例如 javafx-graphics-17.0.13-win.jar -> 17.0.13 */
    private static final Pattern VERSION_IN_JAR_NAME = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)*)");

    /** 由 {@link #main} 解析后供 {@link #start} 使用（main 必定先于 start 执行）。 */
    private static CliOptions options = CliOptions.parse(new String[0]);

    private MainWindow window;

    @Override
    public void start(Stage stage) {
        MachineState machineState = new MachineState(MachineState.defaultDirectory());
        RootResolver.Resolved resolved = resolveInteractiveRoot(stage, machineState);

        window = new MainWindow(stage, resolved);
        window.setMachineState(machineState);
        Scene scene = new Scene(window.getRootPane(), 1320, 720);
        URL css = MainApp.class.getResource(CSS_PATH);
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        } else {
            System.err.println("[MainApp] 警告：未找到样式表 " + CSS_PATH);
        }

        stage.setScene(scene);
        stage.setMinWidth(780);
        stage.setMinHeight(440);
        // 窗口图标（标题栏 + 任务栏）。jpackage 嵌进 .exe 的图标管不到窗口，
        // 这一步不做的话用户看到的是 JavaFX 默认图标
        AppIcons.applyTo(stage);
        stage.show();

        window.startInitialScan();
    }

    @Override
    public void stop() {
        if (window != null) {
            window.shutdown();
        }
    }

    /**
     * 决定交互式启动时管理哪个文件夹。
     *
     * <h2>绿色版与安装版的区别只体现在这里</h2>
     * <ul>
     *   <li><b>绿色版</b>：根目录由启动器位置推出（"程序放在哪个文件夹就管哪个文件夹"），
     *       什么都不用问。</li>
     *   <li><b>安装版</b>：启动器在 {@code %LOCALAPPDATA%\Programs\FilePanel\}，
     *       那个目录没有任何值得管理的东西。所以要么有 {@code --root}，
     *       要么有"上次管理的目录"，否则必须问用户。</li>
     * </ul>
     * 判定条件抽在 {@link RootResolver#requiresFolderChoice} 里，便于单测覆盖
     * （弹窗本身没法自动断言，"什么时候该弹"可以）。
     *
     * <p>用户取消选择时<b>不退出程序</b>，而是退回主目录兜底：让窗口照常出现，
     * 标题与左上角会写明当前管的是哪个目录，用户可以随时用"切换文件夹"改。
     * 双击图标却什么都不发生，是最让人困惑的失败方式。
     */
    private static RootResolver.Resolved resolveInteractiveRoot(Stage stage, MachineState machineState) {
        boolean installed = RootResolver.isInstalledMode();
        boolean explicit = options.root() != null;
        var remembered = machineState.lastRoot();
        boolean hasRemembered = remembered != null;

        if (RootResolver.requiresFolderChoice(explicit, hasRemembered, installed)) {
            System.out.println("  安装版首次启动：请选择要管理的文件夹");
            java.io.File chosen = new DirectoryChooser()
                    .showDialog(stage == null ? null : stage);
            if (chosen != null) {
                return RootResolver.resolve(chosen.toPath(), null);
            }
            System.out.println("  未选择文件夹，暂用主目录兜底（可用界面上的「切换文件夹」更改）");
            return RootResolver.resolve(null, null);
        }
        return RootResolver.resolve(options.root(), remembered);
    }

    // ------------------------------------------------------------------ 入口

    public static void main(String[] args) {
        options = CliOptions.parse(args);

        if (options.help()) {
            System.out.println(CliOptions.helpText());
            return;
        }
        if (!options.unknownOptions().isEmpty()) {
            // 拼错的选项绝不能"静默打开界面"：命令行调用方会一直等一个永远不会退出的进程
            System.err.println("[失败] 无法识别的参数：" + String.join("　", options.unknownOptions()));
            System.err.println();
            System.err.println(CliOptions.helpText());
            System.exit(2);
        }
        if (options.selfTest()) {
            System.exit(runSelfTest() ? 0 : 1);
        }
        if (options.scan()) {
            System.exit(runHeadlessScan() ? 0 : 1);
        }
        if (options.uiSelfTest()) {
            System.exit(runUiSelfTest() ? 0 : 1);
        }
        if (options.screenshot() != null) {
            System.exit(runScreenshot(options.screenshot()) ? 0 : 1);
        }
        if (options.recycleTest() != null) {
            System.exit(runRecycleTest(options.recycleTest()) ? 0 : 1);
        }
        if (options.contentSearch() != null) {
            System.exit(runContentSearch(options.contentSearch()) ? 0 : 1);
        }
        if (options.deleteTest() != null) {
            System.exit(runDeleteTest(options.deleteTest()) ? 0 : 1);
        }
        if (options.settingsPreview() != null) {
            System.exit(runSettingsPreview(options.settingsPreview()) ? 0 : 1);
        }
        if (options.createPreview() != null) {
            System.exit(runCreatePreview(options.createPreview()) ? 0 : 1);
        }
        if (options.deletePreview() != null) {
            System.exit(runDeletePreview(options.deletePreview()) ? 0 : 1);
        }
        if (options.iconSheet() != null) {
            System.exit(runIconSheet(options.iconSheet()) ? 0 : 1);
        }
        launch(args);
    }

    // --------------------------------------------------- 内容搜索验证模式

    /**
     * 无界面的全文内容搜索：建立索引 → 搜索 → 输出命中与摘要。
     *
     * <p>这里刻意用<b>同步</b>建索引而不是复用后台的 {@link ContentIndexer}：
     * 命令行模式要的是确定性输出（打了多少个、花了多久），异步反而让结果不好断言。
     */
    private static boolean runContentSearch(String keyword) {
        System.out.println("=== " + APP_NAME + " 内容搜索 ===");

        RootResolver.Resolved resolved = RootResolver.resolve(options.root(), null);
        Path root = resolved.root();
        System.out.println("  根目录：" + root);
        System.out.println("  关键词：" + keyword);

        Exclusions exclusions = Exclusions.forRoot(root);
        ScanResult scan = new Scanner(exclusions, null).scan(root, () -> false);
        System.out.println("  扫描到 " + FormatUtil.count(scan.fileCount()) + " 个文件");

        ContentIndex index = new ContentIndex(root.resolve(ConfigStore.DIR_NAME));
        int pruned = index.pruneMissing();
        List<FileItem> candidates = ContentIndexer.eligible(scan.items());
        System.out.println("  可抽取正文的文件：" + candidates.size()
                + "（类型受支持且不超过 " + (ContentExtractor.MAX_FILE_BYTES / 1024 / 1024) + " MB）"
                + (pruned > 0 ? "；清理了 " + pruned + " 条失效索引" : ""));

        long startedAt = System.currentTimeMillis();
        int indexed = 0;
        int reused = 0;
        int failed = 0;
        Map<ContentExtractor.Skip, Integer> skipReasons = new java.util.EnumMap<>(
                ContentExtractor.Skip.class);
        List<String> failureDetails = new java.util.ArrayList<>();
        for (FileItem item : candidates) {
            if (index.isFull()) {
                System.out.println("  [提示] 已达索引容量上限，其余文件未索引");
                break;
            }
            if (index.isIndexed(item)) {
                reused++;
                continue;
            }
            ContentExtractor.Extraction extraction = ContentExtractor.extract(item.path());
            if (extraction.succeeded()) {
                long modified = item.modified() == null ? 0L : item.modified().toMillis();
                if (index.put(item.path(), item.size(), modified,
                        extraction.text(), extraction.truncated())) {
                    indexed++;
                }
            } else {
                failed++;
                skipReasons.merge(extraction.skip(), 1, Integer::sum);
                // 只给"数量"是不够的：用户看到"跳过 2 个"无法判断是哪个文件、
                // 也无法判断这是不是自己关心的那份文档。文件名与原因都必须打出来。
                failureDetails.add(item.relPath() + "　（" + extraction.skip().label() + "："
                        + extraction.detail() + "）");
            }
        }
        index.save();
        long elapsed = System.currentTimeMillis() - startedAt;
        System.out.println("  本次新索引：" + indexed + " 个；复用已有：" + reused
                + " 个；跳过：" + failed + " 个；耗时 " + FormatUtil.duration(elapsed));
        if (!skipReasons.isEmpty()) {
            StringBuilder reasons = new StringBuilder();
            skipReasons.forEach((skip, count) ->
                    reasons.append(skip.label()).append(' ').append(count).append(" / "));
            System.out.println("  跳过原因：" + reasons);
            for (String detail : failureDetails) {
                System.out.println("    - " + detail);
            }
        }
        System.out.println("  索引内共 " + index.size() + " 个文件，"
                + FormatUtil.count(index.totalChars()) + " 个字符");

        SearchQuery query = SearchParser.parse("content:" + keyword);
        List<FileItem> hits = new java.util.ArrayList<>();
        for (FileItem item : scan.items()) {
            if (query.matches(item, Set.of(), false, index.textOf(item))) {
                hits.add(item);
            }
        }
        System.out.println();
        System.out.println("  命中 " + hits.size() + " 个文件：");
        for (FileItem item : hits) {
            System.out.println("    · " + item.relPath());
            System.out.println("        " + new SearchQuery.ContentTerm(keyword)
                    .snippet(index.textOf(item)));
        }
        System.out.println(hits.isEmpty()
                ? "=== 内容搜索完成（无命中）==="
                : "=== 内容搜索完成 ===");
        return true;
    }

    // ------------------------------------------------------- 回收站验证模式

    /**
     * 验证"删除是否真的进了回收站"。
     *
     * <p>存在的理由：这件事没法靠"看起来删掉了"判断——文件从原位置消失，
     * 既可能是进了回收站（可还原），也可能是被永久删除了（不可恢复），
     * 而两者的用户代价天差地别；{@code SHFileOperationW} 在这两种情况下都会返回成功。
     *
     * <p>判定方法：比对操作前后 {@code SHQueryRecycleBin} 给出的回收站条目数，
     * 条目数增加才说明文件真的进了回收站。
     *
     * <p>用法：{@code FilePanel.exe --recycle-test "D:\临时\待删除.txt"}
     */
    private static boolean runRecycleTest(Path target) {
        System.out.println("=== " + APP_NAME + " 回收站验证 ===");
        Path absolute = target.toAbsolutePath().normalize();
        System.out.println("  目标：" + absolute);

        if (!Files.exists(absolute)) {
            System.out.println("  [失败] 文件不存在");
            return false;
        }
        if (Files.isDirectory(absolute)) {
            System.out.println("  [失败] 这是目录；请用一个临时文件来验证");
            return false;
        }
        if (!Shell32.available()) {
            System.out.println("  [失败] 无法加载 shell32，回收站功能不可用");
            return false;
        }

        long before = Shell32.recycleBinItemCount(null);
        if (before < 0) {
            System.out.println("  [失败] 无法查询回收站条目数，无法完成验证");
            return false;
        }
        System.out.println("  删除前回收站条目数：" + before);

        Shell32.Result result = Shell32.moveToRecycleBin(List.of(absolute));
        System.out.println("  Shell 返回：成功=" + result.success()
                + " 错误码=" + result.code()
                + (result.message().isEmpty() ? "" : "（" + result.message() + "）"));

        if (!result.success()) {
            System.out.println("  [失败] 送入回收站未成功；文件应当还在原处："
                    + Files.exists(absolute));
            return false;
        }
        System.out.println("  文件是否还在原位置：" + Files.exists(absolute));

        long after = Shell32.recycleBinItemCount(null);
        System.out.println("  删除后回收站条目数：" + after + "（变化 " + (after - before) + "）");

        if (after - before > 0) {
            System.out.println("=== 结论：文件确实进入了回收站，可以从回收站还原 ===");
            return true;
        }
        System.out.println("=== 结论：文件已从原位置消失，但回收站条目数没有增加 ===");
        System.out.println("    也就是说它很可能被【永久删除】了。");
        System.out.println("    可能原因：系统关闭了回收站、该盘配额不足、文件位于网络位置，");
        System.out.println("    或运行环境限制了写入回收站目录。");
        return false;
    }

    // ------------------------------------------------- 删除分支验证模式

    /**
     * 按<b>当前生效的删除策略</b>真的删掉一个文件，并报告走了哪条分支。
     *
     * <h2>为什么需要它</h2>
     * "不小于阈值 → 永久删除"这条分支以前只有单元测试覆盖：要真机验证就得造一个 1 GB 的文件。
     * 但阈值现在是可配置的，把它调成 1 MB，用一个小文件就能走完同一条分支——
     * 判定只是一次 {@code >=} 比较，与量级无关。这个模式就是那条"用调小阈值来验证"的落地入口，
     * 它跑的是真实的 {@link com.zean.filepanel.ops.DeleteService}，<b>会真的删掉你指定的文件</b>。
     *
     * <p>刻意要求显式传入文件路径，不做任何"找几个文件试试"的猜测：
     * 一个会删文件的命令，绝不能自己挑目标。
     */
    private static boolean runDeleteTest(Path target) {
        System.out.println("=== " + APP_NAME + " 删除分支验证 ===");
        Path absolute = target.toAbsolutePath().normalize();
        System.out.println("  目标：" + absolute);

        if (!Files.isRegularFile(absolute)) {
            System.out.println("  [失败] 不存在，或不是普通文件（本模式只删单个文件）");
            return false;
        }

        RootResolver.Resolved resolved = RootResolver.resolve(options.root(), null);
        Path root = resolved.root();
        ConfigStore configStore = new ConfigStore(root);
        UiState state = configStore.load();
        DeletePolicy policy = new DeletePolicy(state.deleteThresholdBytes, state.alwaysRecycle);

        System.out.println("  根目录：" + root);
        System.out.println("  数据目录：" + root.resolve(ConfigStore.DIR_NAME));
        System.out.println("  配置里的阈值：" + DeletePolicy.humanSize(state.deleteThresholdBytes)
                + "　一律走回收站：" + state.alwaysRecycle);
        System.out.println("  生效的删除策略：" + policy.describe());

        long size;
        try {
            size = Files.size(absolute);
        } catch (IOException e) {
            System.out.println("  [失败] 读不到文件大小：" + e.getMessage());
            return false;
        }

        FileItem item = new FileItem(absolute.getFileName().toString(), absolute,
                absolute.getFileName().toString(), "", false,
                FileItem.extensionOf(absolute.getFileName().toString()),
                FileKind.ofExtension(FileItem.extensionOf(absolute.getFileName().toString())),
                size, null, null, null, 0, false, false, false);

        DeletePolicy.Action action = policy.decide(item);
        System.out.println("  文件大小：" + FormatUtil.size(size));
        System.out.println("  分流结果：" + action
                + (action == DeletePolicy.Action.PERMANENT
                ? "（永久删除，不可从回收站恢复）" : "（送入回收站，可还原）"));

        long before = Shell32.available() ? Shell32.recycleBinItemCount(null) : -1;
        if (before >= 0) {
            System.out.println("  删除前回收站条目数：" + before);
        }

        Path dataDir = root.resolve(ConfigStore.DIR_NAME);
        DeleteService service = new DeleteService(policy, new DeleteJournal(dataDir));
        DeleteService.Outcome outcome = service.execute(List.of(item), DeleteService.Mode.POLICY);

        System.out.println("  实际永久删除：" + outcome.deleted().size() + " 项");
        System.out.println("  实际送入回收站：" + outcome.recycled().size() + " 项");
        for (DeleteService.Failure f : outcome.failures()) {
            System.out.println("  [失败] " + f.item().name() + "：" + f.message());
        }
        System.out.println("  文件是否还在原位置：" + Files.exists(absolute));

        boolean ok = !Files.exists(absolute) && outcome.failures().isEmpty();

        if (action == DeletePolicy.Action.PERMANENT) {
            long after = before < 0 ? -1 : Shell32.recycleBinItemCount(null);
            if (after >= 0) {
                System.out.println("  删除后回收站条目数：" + after + "（变化 " + (after - before) + "）");
                if (after > before) {
                    // 判为永久删除却进了回收站：说明策略与执行对不上，必须报出来
                    System.out.println("  [失败] 按策略应永久删除，回收站条目数却增加了");
                    ok = false;
                }
            }
            System.out.println(ok
                    ? "=== 结论：走了「永久删除」分支，文件已从磁盘消失且未进入回收站 ==="
                    : "=== 结论：永久删除分支验证未通过 ===");
        } else {
            long after = before < 0 ? -1 : Shell32.recycleBinItemCount(null);
            if (after >= 0) {
                System.out.println("  删除后回收站条目数：" + after + "（变化 " + (after - before) + "）");
            }
            System.out.println(ok
                    ? "=== 结论：走了「回收站」分支，文件已移出原位置 ==="
                    : "=== 结论：回收站分支验证未通过 ===");
        }
        System.out.println("  删除日志：" + dataDir.resolve(DeleteJournal.FILE_NAME));
        return ok;
    }

    // ------------------------------------------------- 设置面板截图模式

    /**
     * 把系统真实图标打成一张图集（M8-b 的 PoC 证据）。
     *
     * <h2>为什么这一步不能只看"函数有没有返回非空"</h2>
     * HICON → 像素这段代码有一堆容易"成功但结果是错的"失败方式：结构体字段顺序写错、
     * DIB 读成上下颠倒、透明度判断错导致整块黑色、图标尺寸读成 0……
     * 这些情况下函数<b>都会返回一个非空的像素数组</b>，断言全绿，而界面上是一排黑方块。
     * 所以必须把图集渲染出来看一眼。这也是本项目一贯的做法：有一类缺陷只能"看"出来。
     */
    private static boolean runIconSheet(Path output) {
        System.out.println("=== " + APP_NAME + " 系统图标图集（M8-b PoC）===");
        System.out.println("  输出：" + output.toAbsolutePath());

        List<String> failures = new ArrayList<>();
        System.out.println("  ShellIcons.available() = " + com.zean.filepanel.win.ShellIcons.available());
        System.out.println("  ICONINFO 声明大小 = " + com.zean.filepanel.win.ShellIcons.iconInfoSize()
                + "（64 位下应为 32）");

        // 覆盖常见类型 + 几个"应当取不到"的边界（空扩展名、纯数字扩展名）
        List<String> extensions = List.of("txt", "docx", "xlsx", "pptx", "pdf", "png", "jpg",
                "zip", "rar", "exe", "mp4", "mp3", "sql", "java", "md", "folder", "");

        StringBuilder report = new StringBuilder();
        int ok = 0;
        for (String ext : extensions) {
            com.zean.filepanel.win.ShellIcons.Icon icon =
                    com.zean.filepanel.win.ShellIcons.iconForExtension(ext, true);
            boolean got = icon != null && !icon.isEmpty();
            if (got) {
                ok++;
            }
            report.append(String.format("    %-8s %s%n", ext.isEmpty() ? "(无扩展名)" : ext,
                    got ? icon.width() + "×" + icon.height() + " 像素" : "取不到（会降级为色块）"));
        }
        System.out.print(report);
        System.out.println("  成功 " + ok + " / " + extensions.size());

        Scene[] sceneHolder = new Scene[1];
        CountDownLatch built = new CountDownLatch(1);
        try {
            Platform.startup(() -> {
                try {
                    javafx.scene.layout.TilePane grid = new javafx.scene.layout.TilePane();
                    grid.setHgap(10);
                    grid.setVgap(10);
                    grid.setPadding(new javafx.geometry.Insets(16));
                    grid.getStyleClass().add("dialog-preview");
                    for (String ext : extensions) {
                        com.zean.filepanel.win.ShellIcons.Icon icon =
                                com.zean.filepanel.win.ShellIcons.iconForExtension(ext, true);
                        javafx.scene.image.ImageView view = new javafx.scene.image.ImageView(
                                icon == null ? null : FileIcons.toImage(icon));
                        view.setFitWidth(32);
                        view.setFitHeight(32);
                        view.setPreserveRatio(true);
                        javafx.scene.control.Label caption = new javafx.scene.control.Label(
                                ext.isEmpty() ? "(无)" : "." + ext);
                        caption.getStyleClass().add("dialog-dim");
                        javafx.scene.layout.VBox cell = new javafx.scene.layout.VBox(4, view, caption);
                        cell.setAlignment(javafx.geometry.Pos.CENTER);
                        cell.setPrefWidth(78);
                        grid.getChildren().add(cell);
                    }
                    Scene scene = new Scene(grid);
                    URL css = MainApp.class.getResource(CSS_PATH);
                    if (css != null) {
                        scene.getStylesheets().add(css.toExternalForm());
                    }
                    sceneHolder[0] = scene;
                } catch (Throwable t) {
                    failures.add("构建图集失败：" + t);
                } finally {
                    built.countDown();
                }
            });
        } catch (Throwable t) {
            failures.add("Platform.startup 异常：" + t);
        }
        await(built, 20_000, "等待图标图集构建");
        sleepQuietly(600);

        if (sceneHolder[0] == null) {
            failures.forEach(f -> System.out.println("  [失败] " + f));
            System.out.println("=== 图标图集失败 ===");
            return false;
        }
        Platform.runLater(() -> {
            try {
                writePng(sceneHolder[0].snapshot(null), output);
                System.out.println("  已写出：" + output.toAbsolutePath()
                        + "（" + Files.size(output) + " 字节）");
            } catch (Throwable t) {
                failures.add("写截图失败：" + t);
            }
        });
        sleepQuietly(1200);
        try {
            Platform.exit();
        } catch (Throwable ignored) {
            // 工具包可能已在退出流程中
        }
        if (ok == 0) {
            failures.add("一张图标都没取到——PoC 结论应为「失败，保持降级」");
        }
        if (failures.isEmpty()) {
            System.out.println("=== 图标图集完成 ===");
            return true;
        }
        failures.forEach(f -> System.out.println("  [失败] " + f));
        System.out.println("=== 图标图集失败 ===");
        return false;
    }

    /**
     * 只渲染批量创建面板并截图。
     *
     * <p>存在的理由和设置面板那张一样：<b>下拉框里显示的是中文还是枚举名</b>
     * （{@code 文件夹} vs {@code FOLDER}）、以及输入框的提示词能不能看出位置，
     * 都是单测与自检<b>看不见</b>的东西——它们只在真的把界面画出来时才存在。
     * 实测就是这么漏掉一次的：三个下拉框显示成了 {@code FOLDER / NUMBER / SKIP}。
     */
    private static boolean runCreatePreview(Path output) {
        System.out.println("=== " + APP_NAME + " 批量创建面板截图 ===");

        RootResolver.Resolved resolved = RootResolver.resolve(options.root(), null);
        Path root = resolved.root();
        System.out.println("  目标目录（面板里显示的）：" + root);
        System.out.println("  输出：" + output.toAbsolutePath());

        List<String> failures = new ArrayList<>();
        Scene[] sceneHolder = new Scene[1];
        CountDownLatch built = new CountDownLatch(1);

        try {
            Platform.startup(() -> {
                try {
                    javafx.scene.layout.StackPane holder = new javafx.scene.layout.StackPane(
                            com.zean.filepanel.ui.dialog.BatchCreateDialog
                                    .buildContentForPreview(root, 10));
                    holder.setPadding(new javafx.geometry.Insets(14));
                    holder.getStyleClass().add("dialog-preview");
                    Scene scene = new Scene(holder);
                    URL css = MainApp.class.getResource(CSS_PATH);
                    if (css != null) {
                        scene.getStylesheets().add(css.toExternalForm());
                    } else {
                        failures.add("样式表缺失：" + CSS_PATH);
                    }
                    sceneHolder[0] = scene;
                } catch (Throwable t) {
                    failures.add("构建批量创建面板失败：" + t);
                } finally {
                    built.countDown();
                }
            });
        } catch (Throwable t) {
            failures.add("Platform.startup 异常：" + t);
        }
        await(built, 20_000, "等待批量创建面板构建");
        sleepQuietly(600);

        if (sceneHolder[0] == null) {
            failures.forEach(f -> System.out.println("  [失败] " + f));
            System.out.println("=== 批量创建面板截图失败 ===");
            return false;
        }
        try {
            Platform.runLater(() -> {
                try {
                    writePng(sceneHolder[0].snapshot(null), output);
                    System.out.println("  已写出：" + output.toAbsolutePath()
                            + "（" + Files.size(output) + " 字节）");
                } catch (Throwable t) {
                    failures.add("写截图失败：" + t);
                }
            });
            sleepQuietly(1200);
        } catch (Throwable t) {
            failures.add("截图异常：" + t);
        }
        try {
            Platform.exit();
        } catch (Throwable ignored) {
            // 工具包可能已在退出流程中
        }
        if (failures.isEmpty()) {
            System.out.println("=== 批量创建面板截图完成 ===");
            return true;
        }
        failures.forEach(f -> System.out.println("  [失败] " + f));
        System.out.println("=== 批量创建面板截图失败 ===");
        return false;
    }

    /**
     * 只渲染删除确认框并截图。
     *
     * <p>删除确认框同样用 {@code showAndWait()}，没法"开着它再截图"，所以走和设置面板、
     * 批量创建面板同一条路：直接把面板放进一个独立场景渲染。它值得单独有证据，
     * 因为 D4 的三条安全护栏全在这个框里，而它平时只在点删除时才出现。
     *
     * <p>框里列出的条目取自<b>当前根目录里真实存在的文件</b>（不虚构文件名）：
     * 这样截图里的名字与被管理目录对得上，不会出现"图上有个实际不存在的文件"。
     */
    private static boolean runDeletePreview(Path output) {
        System.out.println("=== " + APP_NAME + " 删除确认框截图 ===");

        RootResolver.Resolved resolved = RootResolver.resolve(options.root(), null);
        Path root = resolved.root();
        UiState state = new ConfigStore(root).load();
        DeletePolicy policy = new DeletePolicy(state.deleteThresholdBytes, state.alwaysRecycle);

        Exclusions exclusions = Exclusions.forRoot(root);
        ScanResult scan = new Scanner(exclusions, null).scan(root, () -> false);
        List<FileItem> items = scan.items().stream()
                .filter(item -> !item.directory())
                .sorted(java.util.Comparator.comparing(FileItem::name))
                .limit(2)
                .toList();
        System.out.println("  根目录：" + root);
        System.out.println("  当前策略：" + policy.describe());
        System.out.println("  列出的条目：" + items.size());
        if (items.isEmpty()) {
            System.out.println("  [失败] 根目录里没有可列出的文件，删除确认框会退化成空框");
            System.out.println("=== 删除确认框截图失败 ===");
            return false;
        }
        System.out.println("  输出：" + output.toAbsolutePath());

        List<String> failures = new ArrayList<>();
        Scene[] sceneHolder = new Scene[1];
        CountDownLatch built = new CountDownLatch(1);

        try {
            Platform.startup(() -> {
                try {
                    javafx.scene.layout.StackPane holder = new javafx.scene.layout.StackPane(
                            com.zean.filepanel.ui.dialog.DeleteConfirmDialog
                                    .buildContentForPreview(items, policy));
                    holder.setPadding(new javafx.geometry.Insets(14));
                    holder.getStyleClass().add("dialog-preview");
                    Scene scene = new Scene(holder);
                    URL css = MainApp.class.getResource(CSS_PATH);
                    if (css != null) {
                        scene.getStylesheets().add(css.toExternalForm());
                    } else {
                        failures.add("样式表缺失：" + CSS_PATH);
                    }
                    sceneHolder[0] = scene;
                } catch (Throwable t) {
                    failures.add("构建删除确认框失败：" + t);
                } finally {
                    built.countDown();
                }
            });
        } catch (Throwable t) {
            failures.add("Platform.startup 异常：" + t);
        }
        await(built, 20_000, "等待删除确认框构建");
        sleepQuietly(600);

        if (sceneHolder[0] == null) {
            failures.forEach(f -> System.out.println("  [失败] " + f));
            System.out.println("=== 删除确认框截图失败 ===");
            return false;
        }
        try {
            Platform.runLater(() -> {
                try {
                    writePng(sceneHolder[0].snapshot(null), output);
                    System.out.println("  已写出：" + output.toAbsolutePath()
                            + "（" + Files.size(output) + " 字节）");
                } catch (Throwable t) {
                    failures.add("写截图失败：" + t);
                }
            });
            sleepQuietly(1200);
        } catch (Throwable t) {
            failures.add("截图异常：" + t);
        }
        try {
            Platform.exit();
        } catch (Throwable ignored) {
            // 工具包可能已在退出流程中
        }
        if (failures.isEmpty()) {
            System.out.println("=== 删除确认框截图完成 ===");
            return true;
        }
        failures.forEach(f -> System.out.println("  [失败] " + f));
        System.out.println("=== 删除确认框截图失败 ===");
        return false;
    }

    /**
     * 只渲染设置面板并截图。
     *
     * <p>设置对话框用的是 {@code showAndWait()}，它会阻塞 JavaFX 线程，
     * 所以没法"开着对话框再截图"。这里直接把面板放进一个独立场景渲染——
     * 验证的仍然是同一个 {@link com.zean.filepanel.ui.dialog.SettingsDialog.Panel}，
     * 因此它同时是这张界面的<b>布局回归证据</b>（CSS 写错时数据正常、界面不可用）。
     */
    private static boolean runSettingsPreview(Path output) {
        System.out.println("=== " + APP_NAME + " 设置面板截图 ===");

        RootResolver.Resolved resolved = RootResolver.resolve(options.root(), null);
        Path root = resolved.root();
        UiState state = new ConfigStore(root).load();
        DeletePolicy policy = new DeletePolicy(state.deleteThresholdBytes, state.alwaysRecycle);
        System.out.println("  根目录：" + root);
        System.out.println("  回显的策略：" + policy.describe());
        System.out.println("  输出：" + output.toAbsolutePath());

        List<String> failures = new ArrayList<>();
        Scene[] sceneHolder = new Scene[1];
        CountDownLatch built = new CountDownLatch(1);

        try {
            Platform.startup(() -> {
                try {
                    javafx.scene.layout.StackPane holder = new javafx.scene.layout.StackPane(
                            com.zean.filepanel.ui.dialog.SettingsDialog.buildContentForPreview(policy));
                    holder.setPadding(new javafx.geometry.Insets(18));
                    holder.getStyleClass().add("dialog-preview");
                    Scene scene = new Scene(holder);
                    URL css = MainApp.class.getResource(CSS_PATH);
                    if (css != null) {
                        scene.getStylesheets().add(css.toExternalForm());
                    } else {
                        failures.add("样式表缺失：" + CSS_PATH);
                    }
                    sceneHolder[0] = scene;
                } catch (Throwable t) {
                    failures.add("构建设置面板失败：" + t);
                } finally {
                    built.countDown();
                }
            });
        } catch (Throwable t) {
            failures.add("Platform.startup 异常：" + t);
        }
        await(built, 20_000, "等待设置面板构建");
        sleepQuietly(600);

        if (sceneHolder[0] == null) {
            failures.forEach(f -> System.out.println("  [失败] " + f));
            System.out.println("=== 设置面板截图失败 ===");
            return false;
        }
        try {
            Platform.runLater(() -> {
                try {
                    writePng(sceneHolder[0].snapshot(null), output);
                    System.out.println("  已写出：" + output.toAbsolutePath()
                            + "（" + Files.size(output) + " 字节）");
                } catch (Throwable t) {
                    failures.add("写截图失败：" + t);
                }
            });
            sleepQuietly(1200);
        } catch (Throwable t) {
            failures.add("截图异常：" + t);
        }
        try {
            Platform.exit();
        } catch (Throwable ignored) {
            // 工具包可能已在退出流程中
        }
        if (failures.isEmpty()) {
            System.out.println("=== 设置面板截图完成 ===");
            return true;
        }
        failures.forEach(f -> System.out.println("  [失败] " + f));
        System.out.println("=== 设置面板截图失败 ===");
        return false;
    }

    // --------------------------------------------------------- 截图模式
    /**
     * 显示真实窗口，等扫描与隐藏内容统计都完成后，用 {@code Scene.snapshot} 把界面渲染成 PNG。
     *
     * <p>存在的理由：单元测试与 {@code --ui-selftest} 都只能验证数据，验证不了 CSS 与布局。
     * 样式表写错（列宽塌陷、文字与背景同色、行高错乱）时数据一切正常，界面却不可用。
     * 这个模式让“界面长什么样”也能留证并在 M2~M6 之间做回归比对。
     *
     * <p>用 {@code Scene.snapshot} 而不是操作系统级截屏：不需要 System.Drawing，
     * 不受窗口遮挡影响，且渲染的就是 JavaFX 自己的合成结果。
     */
    private static boolean runScreenshot(Path output) {
        System.out.println("=== " + APP_NAME + " 界面截图 ===");

        RootResolver.Resolved resolved = RootResolver.resolve(options.root(), null);
        System.out.println("  根目录：" + resolved.root());
        System.out.println("  输出：" + output.toAbsolutePath());

        List<String> failures = new ArrayList<>();
        MainWindow[] holder = new MainWindow[1];
        Scene[] sceneHolder = new Scene[1];
        CountDownLatch started = new CountDownLatch(1);

        try {
            Platform.startup(() -> {
                try {
                    Stage stage = new Stage();
                    MainWindow window = new MainWindow(stage, resolved);
                    Scene scene = new Scene(window.getRootPane(), 1320, 720);
                    URL css = MainApp.class.getResource(CSS_PATH);
                    if (css != null) {
                        scene.getStylesheets().add(css.toExternalForm());
                    } else {
                        failures.add("样式表缺失：" + CSS_PATH);
                    }
                    stage.setScene(scene);
                    stage.show();
                    holder[0] = window;
                    sceneHolder[0] = scene;
                    window.startInitialScan();
                } catch (Throwable t) {
                    failures.add("构建窗口失败：" + t);
                } finally {
                    started.countDown();
                }
            });
        } catch (Throwable t) {
            failures.add("Platform.startup 异常：" + t);
        }

        await(started, 20_000, "等待 JavaFX 工具包就绪");

        MainWindow window = holder[0];
        if (window == null) {
            failures.forEach(f -> System.out.println("  [失败] " + f));
            System.out.println("=== 截图失败 ===");
            return false;
        }

        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline && window.scanCompletionCount() < 1) {
            sleepQuietly(50);
        }
        if (window.scanCompletionCount() < 1) {
            failures.add("等待扫描完成超时（60s）");
        }

        // 可选：切换主题后再截图，用于留下深色模式的界面记录
        if (options.theme() != null && !options.theme().isBlank()) {
            Platform.runLater(() -> window.setTheme(options.theme()));
            sleepQuietly(200);
            System.out.println("  已应用主题：" + options.theme());
        }

        // 可选：先应用一个搜索词，让截图能记录搜索功能的实际样子（M6 文档需要）
        if (options.search() != null && !options.search().isBlank()) {
            Platform.runLater(() -> window.searchNow(options.search()));
            sleepQuietly(300);
            System.out.println("  已应用搜索：" + options.search());

            // 内容搜索必须等索引建完再截图，否则截到的是"0 命中 + 空摘要列"，
            // 看起来像功能坏了。索引本身是增量的，第二次运行几乎瞬间完成。
            if (options.search().contains("content:")) {
                long indexDeadline = System.currentTimeMillis() + 120_000;
                while (System.currentTimeMillis() < indexDeadline && window.contentIndexRunning()) {
                    sleepQuietly(100);
                }
                System.out.println("  等待内容索引完成："
                        + (window.contentIndexRunning() ? "超时（120s）" : "已完成"));
                if (window.contentIndexRunning()) {
                    failures.add("等待内容索引完成超时（120s），截图可能是空的");
                }
                // 索引完成后要重新筛一次，命中数才会更新
                Platform.runLater(() -> window.searchNow(options.search()));
                sleepQuietly(300);
            }
        }

        // 再等一会：让 CSS 应用、布局稳定，并让“已隐藏内容”统计回来写进状态栏
        sleepQuietly(1500);

        CountDownLatch written = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                writePng(sceneHolder[0].snapshot(null), output);
                System.out.println("  已写出：" + output.toAbsolutePath()
                        + "（" + Files.size(output) + " 字节）");
            } catch (Throwable t) {
                failures.add("写截图失败：" + t);
            } finally {
                written.countDown();
            }
        });
        await(written, 30_000, "等待截图写出");

        window.shutdown();
        try {
            Platform.exit();
        } catch (Throwable ignored) {
            // 工具包可能已在退出流程中
        }

        if (failures.isEmpty()) {
            System.out.println("=== 截图完成 ===");
            return true;
        }
        failures.forEach(f -> System.out.println("  [失败] " + f));
        System.out.println("=== 截图失败 ===");
        return false;
    }

    /**
     * 把 JavaFX 图像写成 PNG。
     *
     * <p>刻意不用 {@code SwingFXUtils}：那需要额外引入 {@code javafx.swing} 模块，
     * 而为了一行像素搬运增加一个模块依赖不划算。直接用 {@code PixelReader} 取 ARGB 整数数组，
     * 一次性灌进 {@code BufferedImage}。
     */
    private static void writePng(javafx.scene.image.Image image, Path output) throws Exception {
        int width = (int) Math.round(image.getWidth());
        int height = (int) Math.round(image.getHeight());
        if (width <= 0 || height <= 0) {
            throw new IllegalStateException("截图尺寸非法：" + width + "x" + height);
        }
        int[] pixels = new int[width * height];
        image.getPixelReader().getPixels(0, 0, width, height,
                javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, width);

        java.awt.image.BufferedImage buffered =
                new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        buffered.setRGB(0, 0, width, height, pixels, 0, width);

        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        javax.imageio.ImageIO.write(buffered, "png", output.toFile());
    }

    // --------------------------------------------------- 界面链路自检模式

    /**
     * 界面链路自检：构建<b>真实的</b> {@link MainWindow} 并跑完整条链路
     * （解析根目录 → 后台扫描 → 加载页推进度 → 表格填充 → 状态栏汇总），最后输出各环节的实际数字。
     *
     * <p>与 {@code --scan} 的分工：{@code --scan} 证明「扫描器算对了」，
     * 本模式证明「表格真的出表了、状态栏真的有内容」。两者缺一不可——
     * 扫描正确但绑定断裂（例如忘记把结果塞进 ObservableList）是很容易发生的错误，
     * 而只看扫描统计发现不了。
     *
     * <p>刻意不调用 {@code stage.show()}：自动验证不应在用户屏幕上闪窗口。
     * 代价是只验证数据链路，不验证像素渲染；像素渲染由人工验收覆盖。
     */
    private static boolean runUiSelfTest() {
        System.out.println("=== " + APP_NAME + " 界面自检 ===");

        RootResolver.Resolved resolved = RootResolver.resolve(options.root(), null);
        System.out.println("  根目录：" + resolved.root());
        System.out.println("  来源：" + resolved.reason());

        List<String> failures = new ArrayList<>();
        MainWindow[] holder = new MainWindow[1];
        int[] iconsApplied = new int[1];
        CountDownLatch started = new CountDownLatch(1);

        try {
            Platform.startup(() -> {
                try {
                    Stage stage = new Stage();
                    MainWindow window = new MainWindow(stage, resolved);
                    Scene scene = new Scene(window.getRootPane(), 1320, 720);
                    URL css = MainApp.class.getResource(CSS_PATH);
                    if (css != null) {
                        scene.getStylesheets().add(css.toExternalForm());
                    } else {
                        failures.add("样式表缺失：" + CSS_PATH);
                    }
                    stage.setScene(scene);
                    // 与正式启动走同一条图标接线，并记录实际生效的张数供断言
                    iconsApplied[0] = AppIcons.applyTo(stage);
                    holder[0] = window;
                    window.startInitialScan();
                } catch (Throwable t) {
                    failures.add("构建窗口失败：" + t);
                } finally {
                    started.countDown();
                }
            });
        } catch (Throwable t) {
            failures.add("Platform.startup 异常：" + t);
        }

        await(started, 20_000, "等待 JavaFX 工具包就绪");

        MainWindow window = holder[0];
        if (window == null) {
            failures.forEach(f -> System.out.println("  [失败] " + f));
            System.out.println("=== 界面自检未通过 ===");
            return false;
        }

        // 等待后台扫描结束（完成后 lastResult 才会被赋值）
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline && window.scanCompletionCount() < 1) {
            sleepQuietly(50);
        }
        if (window.scanCompletionCount() < 1) {
            failures.add("等待扫描完成超时（60s）");
        }

        // 读取界面状态必须在 FX 线程上做，否则可能读到半更新的集合
        CountDownLatch readDone = new CountDownLatch(1);
        StringBuilder snapshot = new StringBuilder();
        Platform.runLater(() -> {
            try {
                ScanResult result = window.lastResult();
                if (result == null) {
                    return;
                }
                long visible = window.visibleItems().size();
                long progressEvents = window.progressEventCount();
                String status = window.statusText();

                snapshot.append("  扫描结果：文件 ").append(FormatUtil.count(result.fileCount()))
                        .append(" 个 · 文件夹 ").append(FormatUtil.count(result.dirCount()))
                        .append(" 个 · 占用 ").append(FormatUtil.size(result.totalBytes())).append('\n');
                snapshot.append("  表格现在展示的条目数（默认只显示文件）：").append(visible).append('\n');
                snapshot.append("  进度回调次数：").append(progressEvents).append('\n');
                snapshot.append("  状态栏：").append(status).append('\n');

                // 打印真实单元格文本：像素截图读不准的细节（分隔符、截断）用文本核对
                snapshot.append("  前几行的单元格实际文本：\n");
                window.visibleItems().stream().limit(5).forEach(item ->
                        snapshot.append("    ").append(item.name())
                                .append("  |  ").append(item.typeText())
                                .append("  |  ").append(FormatUtil.size(item.size()))
                                .append("  |  ").append(item.locationText())
                                .append("  |  ").append(item.relPath())
                                .append('\n'));

                if (visible != result.fileCount()) {
                    failures.add("表格条目数（" + visible + "）与扫描到的文件数（"
                            + result.fileCount() + "）不一致，说明扫描结果没有正确绑定到表格");
                }
                if (progressEvents < 1) {
                    failures.add("未收到任何进度回调，加载页无法显示真实进度");
                }
                if (status == null || status.isBlank() || !status.contains("共")) {
                    failures.add("状态栏内容异常：" + status);
                }
                if (result.hasErrors()) {
                    failures.add("扫描出现 " + result.errorCount() + " 个读取失败的位置");
                }

                // 回归断言：默认排序必须真的生效。
                // 曾经出现过“表头箭头显示已按名称升序、实际却是扫描顺序”的问题——
                // 仅看数据完全正常，只有比对顺序才能发现。
                java.util.Comparator<String> byName = FormatUtil.chineseText();
                java.util.List<FileItem> rows = window.visibleItems();
                for (int i = 1; i < rows.size(); i++) {
                    if (byName.compare(rows.get(i - 1).name(), rows.get(i).name()) > 0) {
                        failures.add("默认排序未生效：第 " + i + " 行「" + rows.get(i - 1).name()
                                + "」排在了「" + rows.get(i).name() + "」之前");
                        break;
                    }
                }

                // “显示文件夹”开关（设计决策 D7：默认隐藏目录，提供开关）
                window.setShowFolders(true);
                long withFolders = window.visibleItems().size();
                window.setShowFolders(false);
                long filesOnly = window.visibleItems().size();
                snapshot.append("  「显示文件夹」开关：打开时 ").append(withFolders)
                        .append(" 行，关闭时 ").append(filesOnly).append(" 行\n");

                if (filesOnly != result.fileCount()) {
                    failures.add("关闭“显示文件夹”后应只显示文件（" + result.fileCount()
                            + "），实际 " + filesOnly);
                }
                if (withFolders != result.fileCount() + result.dirCount()) {
                    failures.add("打开“显示文件夹”后应显示文件+文件夹（"
                            + (result.fileCount() + result.dirCount()) + "），实际 " + withFolders);
                }
                window.setShowFolders(false);

                runSearchChecks(window, failures, snapshot);
                snapshot.append(window.contentSearchRoundTrip(failures));
            } catch (Throwable t) {
                failures.add("读取界面状态失败：" + t);
            } finally {
                readDone.countDown();
            }
        });
        await(readDone, 20_000, "等待界面状态快照");

        System.out.print(snapshot);

        // 窗口图标：断言"资源真的在 jar 里且能解码、并且已经加到窗口上"。
        // 说明：标题栏本身<b>无法</b>用 Scene.snapshot 截到（它不属于场景内容），
        // 所以这里的证据是"每档尺寸都加载成功 + 窗口的图标列表里确实有它们"，
        // 至于看起来对不对，只能由人看窗口——这一点在文档里如实写明。
        StringBuilder iconReport = new StringBuilder("  --- 窗口图标 ---\n");
        int loadable = 0;
        for (Integer size : AppIcons.sizes()) {
            javafx.scene.image.Image image = AppIcons.load(size);
            boolean ok = image != null && !image.isError() && image.getWidth() == size;
            if (ok) {
                loadable++;
            }
            iconReport.append("    声明 ").append(size).append("×").append(size).append(" → ")
                    .append(ok ? "已加载" : "缺失或尺寸不符").append('\n');
        }
        iconReport.append("    已加到窗口上的图标数：").append(iconsApplied[0])
                .append("（应为 ").append(AppIcons.sizes().size()).append("）\n");
        System.out.print(iconReport);
        if (loadable != AppIcons.sizes().size()) {
            failures.add("窗口图标资源缺失或尺寸不符（" + loadable + "/" + AppIcons.sizes().size()
                    + "），用户会看到 JavaFX 默认图标");
        }
        if (iconsApplied[0] != AppIcons.sizes().size()) {
            failures.add("图标没有全部加到窗口上（" + iconsApplied[0] + "）");
        }

        window.shutdown();
        try {
            Platform.exit();
        } catch (Throwable ignored) {
            // 工具包可能已在退出流程中
        }

        if (failures.isEmpty()) {
            System.out.println("=== 界面自检通过 ===");
            return true;
        }
        failures.forEach(f -> System.out.println("  [失败] " + f));
        System.out.println("=== 界面自检未通过 ===");
        return false;
    }

    /**
     * M2 的搜索与分类断言。
     *
     * <p>重点有两类：
     * <ol>
     *   <li><b>结果不变式</b>：搜索返回的每一项都必须真的满足条件。只断言“命中数”是不够的——
     *       谓词写反（比如把 {@code >} 实现成 {@code <}）时命中数依然是个正常数字，
     *       只有逐项检查才能发现。</li>
     *   <li><b>计数一致性</b>：侧栏显示的计数必须等于点进去以后表格的实际行数。
     *       这两处一旦用了不同的判定逻辑，就会出现“侧栏说 20 项、点进去 19 行”这种无法解释的现象，
     *       而它偏偏不会让任何单元测试变红。</li>
     * </ol>
     */
    private static void runSearchChecks(MainWindow window, List<String> failures, StringBuilder snapshot) {
        snapshot.append("  --- 搜索与分类 ---\n");

        // 1) 类型筛选：逐项验证类型
        window.searchNow("type:image");
        List<FileItem> images = window.visibleItems();
        long imageMillis = window.lastFilterMillis();
        if (images.isEmpty()) {
            failures.add("搜索 type:image 应至少命中一项（当前目录确有图片）");
        }
        for (FileItem item : images) {
            if (item.kind() != FileKind.IMAGE) {
                failures.add("type:image 命中了非图片项：" + item.name() + "（" + item.kind() + "）");
                break;
            }
        }

        // 2) 多条件 AND：加条件不应让命中变多，且每一项都要同时满足
        window.searchNow("type:image size:>1MB");
        List<FileItem> bigImages = window.visibleItems();
        if (bigImages.size() > images.size()) {
            failures.add("增加条件后命中数变多了：" + images.size() + " -> " + bigImages.size());
        }
        for (FileItem item : bigImages) {
            if (item.kind() != FileKind.IMAGE || item.size() <= 1024L * 1024) {
                failures.add("type:image size:>1MB 命中了不满足条件的项：" + item.name()
                        + "（" + item.kind() + " / " + FormatUtil.size(item.size()) + "）");
                break;
            }
        }

        // 3) 通配符
        window.searchNow("*.docx");
        int docxCount = window.visibleItems().size();
        for (FileItem item : window.visibleItems()) {
            if (!Glob.lower(item.name()).endsWith(".docx")) {
                failures.add("*.docx 命中了非 docx 项：" + item.name());
                break;
            }
        }

        // 4) 不存在的关键词应为 0 项
        window.searchNow("zzz这个词肯定不存在zzz");
        if (!window.visibleItems().isEmpty()) {
            failures.add("不存在的关键词应命中 0 项，实际 " + window.visibleItems().size());
        }
        long missMillis = window.lastFilterMillis();

        // 5) 搜索框应把解析结果回显出来（用户靠它确认语法被理解成了什么）
        window.searchNow("type:image size:>1MB");
        if (window.searchHint().isBlank()) {
            failures.add("搜索框未显示解析结果提示");
        }
        String hint = window.searchHint();

        // 6) 筛选耗时预算：计划要求 < 200ms
        long worst = Math.max(imageMillis, missMillis);
        if (worst >= 200) {
            failures.add("筛选耗时 " + worst + " ms，超过 200ms 预算");
        }

        // 7) 侧栏计数与表格行数逐一比对（含分类内类型纯净性）
        window.searchNow("");
        List<String> categoryIds = window.sidebarCategoryIds();
        for (String id : categoryIds) {
            window.selectCategory(id);
            long rows = window.visibleItems().size();
            Long counted = window.sidebarCounts().get(id);
            if (counted == null) {
                failures.add("侧栏缺少分类计数：" + id);
                continue;
            }
            if (rows != counted) {
                failures.add("分类 " + id + "：侧栏计数 " + counted + " 与表格行数 " + rows + " 不一致");
            }
            if (id.startsWith("kind:")) {
                FileKind expected = FileKind.ofKey(id.substring("kind:".length()));
                for (FileItem item : window.visibleItems()) {
                    if (item.kind() != expected) {
                        failures.add("分类 " + id + " 中出现了 " + item.kind() + " 类型的项：" + item.name());
                        break;
                    }
                }
            }
        }
        window.selectCategory(SidebarCategory.ALL);
        window.searchNow("");

        // 划分不变式：类型分类是对条目集合的划分，各分类计数之和必须等于“全部”。
        // 只逐项比对“计数 == 点进去的行数”是不够的——如果同一个条目被算进两个分类，
        // 每个分类单独看都自洽，但总数会对不上。
        Map<String, Long> counts = window.sidebarCounts();
        long kindSum = 0;
        StringBuilder kindBreakdown = new StringBuilder();
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            if (entry.getKey().startsWith("kind:")) {
                kindSum += entry.getValue();
                if (kindBreakdown.length() > 0) {
                    kindBreakdown.append("、");
                }
                kindBreakdown.append(entry.getKey().substring("kind:".length()))
                        .append(' ').append(entry.getValue());
            }
        }
        long allCount = counts.getOrDefault(SidebarCategory.ALL, -1L);
        if (kindSum != allCount) {
            failures.add("类型分类计数之和 " + kindSum + " 与总数 " + allCount
                    + " 不一致：分类应是对全部条目的划分，不能重复也不能遗漏");
        }
        snapshot.append("    类型分类计数：" ).append(kindBreakdown)
                .append("（之和 ").append(kindSum).append("，总数 ").append(allCount).append("）\n");

        snapshot.append("    type:image 命中 ").append(images.size()).append(" 项")
                .append("，叠加 size:>1MB 后 ").append(bigImages.size()).append(" 项\n");
        snapshot.append("    *.docx 命中 ").append(docxCount).append(" 项，不存在的关键词 0 项\n");
        snapshot.append("    搜索提示：").append(hint).append('\n');
        snapshot.append("    筛选耗时：type:image ").append(imageMillis)
                .append(" ms，无命中 ").append(missMillis).append(" ms\n");
        snapshot.append("    侧栏计数一致性：已逐一比对 ").append(categoryIds.size()).append(" 个分类\n");

        runInteractionChecks(window, failures, snapshot);
        runPersistenceChecks(window, failures, snapshot);
        runBatchRenameChecks(window, failures, snapshot);
    }

    /**
     * M5 的批量重命名界面接线断言。
     *
     * <p>刻意<b>不</b>在这里真的执行一次批量重命名：自检跑在你的真实文件夹上。
     * 规则引擎、冲突检测、两阶段执行与撤销分别由
     * {@code RenameRulesTest}（19 个）、{@code BatchRenamePlanTest}（11 个）、
     * {@code BatchRenameServiceTest}（14 个）在临时目录里覆盖，
     * 这里只回答一个问题：<b>入口有没有接上</b>。
     */
    private static void runBatchRenameChecks(MainWindow window, List<String> failures,
                                             StringBuilder snapshot) {
        snapshot.append("  --- 批量重命名 ---\n");

        if (!window.hasToolbarButton("批量重命名")) {
            failures.add("工具栏缺少「批量重命名」按钮");
        }
        if (!window.hasToolbarButton("撤销重命名")) {
            failures.add("工具栏缺少「撤销重命名」按钮");
        }
        if (!window.contextMenuLabels().contains("批量重命名…")) {
            failures.add("右键菜单缺少「批量重命名…」，实际：" + window.contextMenuLabels());
        }

        // 没执行过批量重命名时，撤销入口必须是灰的——否则用户点了会一头雾水
        if (window.canUndoRename()) {
            failures.add("尚未执行批量重命名，不应存在可撤销记录");
        }
        if (window.undoButtonEnabled() != window.canUndoRename()) {
            failures.add("撤销按钮的可用状态与实际情况不一致：按钮="
                    + window.undoButtonEnabled() + "，实际=" + window.canUndoRename());
        }

        snapshot.append("    工具栏按钮：批量重命名 / 撤销重命名 均存在\n");
        snapshot.append("    撤销入口：可用=").append(window.undoButtonEnabled())
                .append("（").append(window.undoDescription()).append("）\n");
    }

    /**
     * M4 的持久化与最近使用断言。
     *
     * <p>这里不写"重启后再读"的断言——自检只跑一次进程，做不到真正的重启。
     * 所以改为验证更本质的东西：<b>数据真的落盘了、并且能原样读回来</b>。
     * 真正的"重启后仍在"由各 store 的单测（写入后用新实例读取）覆盖。
     */
    private static void runPersistenceChecks(MainWindow window, List<String> failures,
                                             StringBuilder snapshot) {
        snapshot.append("  --- 持久化与最近使用 ---\n");

        // 1) 索引缓存：扫描后应写出，并能按当前根目录读回相同条目数
        String cacheStatus = window.indexCacheStatus();
        snapshot.append("    索引缓存：").append(cacheStatus).append('\n');
        if (!cacheStatus.contains("存在=true")) {
            failures.add("扫描完成后应写出索引缓存，实际：" + cacheStatus);
        }
        if (!cacheStatus.contains("可读=true")) {
            failures.add("索引缓存应能被读回，实际：" + cacheStatus);
        }
        long fileCount = window.lastResult() == null ? -1 : window.lastResult().fileCount();
        if (!cacheStatus.contains("缓存文件数=" + fileCount)) {
            failures.add("缓存条目数与当前文件数（" + fileCount + "）不一致：" + cacheStatus);
        }

        // 1b) 秒开：第二次启动应当是用缓存先把列表画出来，而不是等扫描完成
        snapshot.append("    本次启动是否秒开（用上缓存）：").append(window.cacheUsedAtStartup())
                .append("，用缓存出列表耗时 ").append(window.cacheLoadMillis()).append(" ms\n");
        if (window.cacheUsedAtStartup() && window.cacheLoadMillis() > 1000) {
            failures.add("用缓存出列表耗时 " + window.cacheLoadMillis() + " ms，超过 1 秒预算");
        }

        // 2) 收藏：走"点击星标"的同一条代码路径，切换后还原
        String favoriteTrip = window.favoriteToggleRoundTrip();
        snapshot.append("    收藏切换往返：").append(favoriteTrip).append('\n');
        if (!favoriteTrip.contains("结论=一致")) {
            failures.add("表格星标切换没有正确联动存储层：" + favoriteTrip);
        }

        // 3) 标签与收藏的存储层往返（写入 -> 读回 -> 还原）
        String storeTrip = window.storeRoundTripStatus();
        snapshot.append("    存储往返：").append(storeTrip).append('\n');
        if (!storeTrip.contains("结论=一致")) {
            failures.add("收藏/标签的写入读回不一致：" + storeTrip);
        }

        // 4) 最近使用卡片区的折叠开关
        boolean before = window.recentStripCollapsed();
        window.setRecentStripCollapsed(!before);
        boolean after = window.recentStripCollapsed();
        window.setRecentStripCollapsed(before);
        snapshot.append("    最近使用折叠：").append(before).append(" -> ").append(after)
                .append("（已还原为 ").append(window.recentStripCollapsed()).append("）\n");
        if (after == before) {
            failures.add("最近使用卡片区的折叠开关没有生效");
        }

        // 5) 侧栏分类应包含“全部”，有收藏时应有“收藏”入口
        List<String> categories = window.sidebarCategoryIds();
        snapshot.append("    侧栏分类：").append(String.join(" / ", categories)).append('\n');
        if (!categories.contains(SidebarCategory.ALL)) {
            failures.add("侧栏应始终有“全部”分类");
        }
        long favorites = window.favoriteCount();
        if (favorites > 0 && !categories.contains(SidebarCategory.FAVORITES)) {
            failures.add("有 " + favorites + " 条收藏，但侧栏没有“收藏”入口");
        }
    }

    /**
     * M3 的交互与删除策略断言。
     *
     * <p>刻意<b>不</b>在这里真的执行删除或重命名：自检跑在用户的真实文件夹上，
     * 任何写操作都可能造成不可挽回的后果。删除规则本身由
     * {@code DeletePolicyTest} 与 {@code DeleteServiceTest} 在临时目录里穷举覆盖；
     * 这里只验证"界面接线是否正确"——菜单项齐不齐、选中是否可用、策略是否算得出来。
     */
    private static void runInteractionChecks(MainWindow window, List<String> failures, StringBuilder snapshot) {
        snapshot.append("  --- 交互与删除策略 ---\n");

        if (!window.openSupported()) {
            failures.add("当前环境不支持打开文件（Desktop.ACTION_OPEN 不可用）");
        }

        List<String> menu = window.contextMenuLabels();
        for (String expected : List.of("打开", "打开所在文件夹", "重命名", "复制路径", "删除…", "刷新")) {
            if (!menu.contains(expected)) {
                failures.add("右键菜单缺少「" + expected + "」，实际：" + menu);
            }
        }

        window.selectRow(0);
        List<FileItem> selected = window.selectedItems();
        if (selected.size() != 1) {
            failures.add("选中一行后 selectedItems 应为 1 项，实际 " + selected.size());
        }
        String plan = window.deletePlanForSelection();
        if (plan.isBlank()) {
            failures.add("选中文件后应能算出删除方案描述");
        }

        snapshot.append("    右键菜单：").append(String.join(" / ", menu)).append('\n');
        snapshot.append("    删除策略：").append(window.deletePolicyDescription()).append('\n');
        if (!selected.isEmpty()) {
            snapshot.append("    对选中项（").append(selected.get(0).name()).append("）的方案：")
                    .append(plan).append('\n');
        }

        snapshot.append(window.deleteThresholdRoundTrip(failures));
        snapshot.append(window.searchScopeRoundTrip(failures));
        snapshot.append(window.iconRoundTrip(failures));
        snapshot.append(window.staleReferenceCheck(failures));
        snapshot.append("  --- 批量创建（M10）---\n");
        snapshot.append(window.batchCreateSelfCheck(failures));
        snapshot.append(com.zean.filepanel.ui.dialog.SettingsDialog.selfCheck(
                window.currentDeletePolicy(), failures));
    }

    private static void await(CountDownLatch latch, long millis, String what) {
        try {
            if (!latch.await(millis, TimeUnit.MILLISECONDS)) {
                System.err.println("  [警告] " + what + " 超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------- 无界面扫描模式

    /**
     * 无界面扫描：把扫描统计打到标准输出，退出码表示是否正常完成。
     *
     * <p>输出刻意与界面状态栏使用同一套格式化函数，避免“界面一套数字、日志另一套数字”。
     */
    private static boolean runHeadlessScan() {
        System.out.println("=== " + APP_NAME + " 扫描测试 ===");

        Path target = options.scanTarget();
        String reason;
        if (target != null) {
            reason = "命令行 --scan 指定";
        } else {
            RootResolver.Resolved resolved = RootResolver.resolve(options.root(), null);
            target = resolved.root();
            reason = resolved.reason();
        }

        Path absRoot = target.toAbsolutePath().normalize();
        System.out.println("  根目录：" + absRoot);
        System.out.println("  来源：" + reason);

        if (!Files.isDirectory(absRoot) || !Files.isReadable(absRoot)) {
            System.out.println("  [失败] 该路径不存在、不是目录或不可读");
            return false;
        }

        Exclusions exclusions = Exclusions.forRoot(absRoot);
        System.out.println("  排除规则：" + exclusions.describe());

        Scanner scanner = new Scanner(exclusions, null);
        long scanStartedAt = System.currentTimeMillis();
        ScanResult result = scanner.scan(absRoot, () -> false);
        long scanWall = System.currentTimeMillis() - scanStartedAt;

        long measureStartedAt = System.currentTimeMillis();
        HiddenStats measured = Scanner.measureHidden(
                result.excludedDirs(), () -> false, Scanner.MAX_HIDDEN_MEASURE_FILES);
        long measureWall = System.currentTimeMillis() - measureStartedAt;
        HiddenStats hidden = Scanner.totalHidden(result, measured);

        System.out.println("  文件：" + FormatUtil.count(result.fileCount())
                + " 个（" + FormatUtil.size(result.totalBytes()) + "）");
        System.out.println("  文件夹：" + FormatUtil.count(result.dirCount()) + " 个");
        System.out.println("  已隐藏：" + FormatUtil.count(hidden.fileCount() + hidden.dirCount())
                + " 项（" + FormatUtil.size(hidden.bytes()) + "）"
                + (hidden.truncated() ? " [已达统计上限，实际更多]" : ""));
        System.out.println("  隐藏属性项：" + FormatUtil.count(result.hiddenCount()));
        System.out.println("  读取失败：" + FormatUtil.count(result.errorCount()) + " 个");
        for (ScanError error : result.errors().stream().limit(10).toList()) {
            System.out.println("    - " + error.summary());
        }
        System.out.println("  被整目录跳过：" + result.excludedDirs().size() + " 个");
        for (Path dir : result.excludedDirs().stream().limit(10).toList()) {
            System.out.println("    - " + relativize(absRoot, dir));
        }
        System.out.println("  扫描耗时：" + FormatUtil.duration(result.elapsedMillis())
                + "（含隐藏统计共 " + FormatUtil.duration(scanWall + measureWall) + "）");
        System.out.println("  截断：" + (result.truncated() ? "是" : "否")
                + "　取消：" + (result.cancelled() ? "是" : "否"));

        if (result.hasErrors()) {
            System.out.println("=== 扫描完成（存在 " + result.errorCount() + " 个读取失败的位置）===");
        } else {
            System.out.println("=== 扫描完成 ===");
        }
        return !result.cancelled();
    }

    private static String relativize(Path root, Path path) {
        try {
            return root.relativize(path).toString();
        } catch (RuntimeException e) {
            return path.toString();
        }
    }

    // ----------------------------------------------------------- 自检模式

    /** 环境信息，同时用于窗口展示与 {@code --selftest} 输出 */
    private static List<String> environmentReport() {
        List<String> lines = new ArrayList<>();
        lines.add("JDK：" + System.getProperty("java.version")
                + "　（" + System.getProperty("java.vendor") + "）");
        lines.add("JavaFX：" + detectJavaFxVersion());
        // java.home 是"免装 Java"最直接的证据：打包后它应当指向应用自带的 runtime 目录，
        // 而不是 C:\jdk17 之类的系统安装位置
        lines.add("运行时：" + System.getProperty("java.home"));
        lines.add("JVM：" + System.getProperty("java.vm.name"));
        lines.add("系统：" + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        lines.add("编码：" + System.getProperty("file.encoding") + "　·　"
                + "工作目录：" + System.getProperty("user.dir"));
        String builtAt = buildInfo("built.at");
        if (builtAt != null) {
            lines.add("构建：" + APP_VERSION + "　·　" + builtAt
                    + "　·　JavaFX 平台 " + buildInfo("javafx.platform"));
        }
        return lines;
    }

    /**
     * 探测 JavaFX 运行时版本。
     *
     * <p>按可靠性从高到低依次尝试，每一种都对应一种真实的加载方式：
     * <ol>
     *   <li><b>加载来源 jar 的文件名</b>（开发期、classpath 方式）：最直接，
     *       如 {@code javafx-graphics-17.0.13-win.jar}。</li>
     *   <li><b>构建期写入的 build-info.properties</b>（jpackage 运行时镜像方式）：
     *       这种模式下 JavaFX 是 jimage 里的模块，既没有 jar 文件名，
     *       module-info 与 MANIFEST 也不带版本号，运行时<b>根本拿不到</b>版本，
     *       只能在构建时固定下来。</li>
     *   <li>系统属性与包实现版本：兜底。</li>
     * </ol>
     * 前两种都会把"证据"一起打出来，因为 M6 要证明的是
     * "在没有装 Java 的机器上，加载的确实是随包自带的那份 JavaFX"。
     */
    static String detectJavaFxVersion() {
        // 1) 加载来源 jar 的文件名 —— 开发期最可靠
        try {
            CodeSource cs = Platform.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                String jarName = new File(cs.getLocation().toURI()).getName();
                Matcher matcher = VERSION_IN_JAR_NAME.matcher(jarName);
                if (matcher.find()) {
                    return matcher.group(1) + "（" + jarName + "）";
                }
                // 运行时镜像模式下这里会是 modules 之类的名字，落到下一来源
            }
        } catch (Throwable ignored) {
            // 落到下一个来源
        }

        // 2) 模块描述符（将来 JavaFX 若补上版本号则会命中）
        try {
            Module m = Platform.class.getModule();
            if (m != null && m.isNamed() && m.getDescriptor() != null) {
                String v = m.getDescriptor().version().map(Object::toString).orElse(null);
                if (v != null && !v.isBlank()) {
                    return v + "（module " + m.getName() + "）";
                }
            }
        } catch (Throwable ignored) {
            // 落到下一个来源
        }

        // 3) 构建期固定的版本 —— 打包后的主要来源
        String fromBuild = buildInfo("javafx.version");
        if (fromBuild != null && !fromBuild.isBlank()) {
            return fromBuild + "（构建时固定；运行时镜像模式无法在运行时读出版本）";
        }

        // 4) 系统属性与包实现版本
        String prop = System.getProperty("javafx.runtime.version");
        if (prop != null && !prop.isBlank()) {
            return prop;
        }
        try {
            Package p = Platform.class.getPackage();
            if (p != null && p.getImplementationVersion() != null) {
                return p.getImplementationVersion();
            }
        } catch (Throwable ignored) {
            // 落到 unknown
        }
        return "unknown";
    }

    /** 读取构建期写入的 build-info.properties；读不到返回 null。 */
    static String buildInfo(String key) {
        try (java.io.InputStream in = MainApp.class.getResourceAsStream("/build-info.properties")) {
            if (in == null) {
                return null;
            }
            java.util.Properties props = new java.util.Properties();
            props.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            return props.getProperty(key);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 无人值守自检：真正启动 JavaFX 工具包（能起来说明原生 dll 加载正常），
     * 校验 CSS 资源与版本信息，然后退出。返回是否全部通过。
     */
    private static boolean runSelfTest() {
        System.out.println("=== " + APP_NAME + " 自检 ===");
        environmentReport().forEach(l -> System.out.println("  " + l));

        List<String> failures = new ArrayList<>();

        URL css = MainApp.class.getResource(CSS_PATH);
        if (css == null) {
            failures.add("样式表缺失：" + CSS_PATH);
        } else {
            System.out.println("  样式表：OK (" + css + ")");
        }

        CountDownLatch latch = new CountDownLatch(1);
        try {
            Platform.startup(() -> {
                try {
                    // 构造一次 Scene，确认 UI 工具链可用
                    new Scene(new StackPane(new Region()), 100, 100);
                    System.out.println("  JavaFX 工具包：OK（窗口系统可用）");
                } catch (Throwable t) {
                    failures.add("JavaFX 工具包启动失败：" + t);
                } finally {
                    latch.countDown();
                }
            });
        } catch (Throwable t) {
            failures.add("Platform.startup 异常：" + t);
        }

        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                failures.add("等待 JavaFX 工具包就绪超时（20s）");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failures.add("自检被中断");
        } finally {
            try {
                Platform.exit();
            } catch (Throwable ignored) {
                // 工具包未起来时 exit 可能抛异常，忽略
            }
        }

        if (failures.isEmpty()) {
            System.out.println("=== 自检通过 ===");
            return true;
        }
        failures.forEach(f -> System.out.println("  [失败] " + f));
        System.out.println("=== 自检未通过 ===");
        return false;
    }
}
