package com.zean.filepanel.ui;

import com.zean.filepanel.core.DeletePlan;
import com.zean.filepanel.core.DeletePolicy;
import com.zean.filepanel.core.Exclusions;
import com.zean.filepanel.core.FileItem;
import com.zean.filepanel.core.FileKind;
import com.zean.filepanel.core.HiddenStats;
import com.zean.filepanel.core.RootResolver;
import com.zean.filepanel.core.ScanError;
import com.zean.filepanel.core.ScanProgress;
import com.zean.filepanel.core.ScanResult;
import com.zean.filepanel.core.Scanner;
import com.zean.filepanel.core.SearchParser;
import com.zean.filepanel.core.SearchScope;
import com.zean.filepanel.core.SearchQuery;
import com.zean.filepanel.ops.BatchRenamePlan;
import com.zean.filepanel.ops.BatchRenameService;
import com.zean.filepanel.ops.BatchCreatePlan;
import com.zean.filepanel.ops.CreateRules;
import com.zean.filepanel.ops.CreateService;
import com.zean.filepanel.store.CreateJournal;
import com.zean.filepanel.win.ShellIcons;
import com.zean.filepanel.ops.ContentExtractor;
import com.zean.filepanel.ops.ContentIndexer;
import com.zean.filepanel.ops.DeleteService;
import com.zean.filepanel.ops.OpenService;
import com.zean.filepanel.ops.RenameRules;
import com.zean.filepanel.ops.RenameService;
import com.zean.filepanel.store.ConfigStore;
import com.zean.filepanel.store.MachineState;
import com.zean.filepanel.store.ContentIndex;
import com.zean.filepanel.store.DeleteJournal;
import com.zean.filepanel.store.RenameJournal;
import com.zean.filepanel.store.FavoriteStore;
import com.zean.filepanel.store.IndexCache;
import com.zean.filepanel.store.RecentStore;
import com.zean.filepanel.store.TagStore;
import com.zean.filepanel.store.UiState;
import com.zean.filepanel.ui.dialog.BatchRenameDialog;
import com.zean.filepanel.ui.dialog.BatchCreateDialog;
import com.zean.filepanel.ui.dialog.DeleteConfirmDialog;
import com.zean.filepanel.ui.dialog.RenameDialog;
import com.zean.filepanel.ui.dialog.SettingsDialog;
import com.zean.filepanel.ui.dialog.TagDialog;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * 主窗口。
 *
 * <p>数据流是一条三级过滤链，每级只负责一件事，互不干扰：
 * <pre>
 *   allItems            扫描产物，唯一数据源
 *     ↓ 搜索谓词（关键词 + 显示文件夹开关）
 *   searchFiltered      搜索命中的集合；侧栏计数就是它的类型分布
 *     ↓ 分类谓词（侧栏选中的分类）
 *   categoryFiltered
 *     ↓ 排序（比较器绑定到 TableView）
 *   sortedItems         表格实际展示的内容
 * </pre>
 * 分成两级而不是揉成一个谓词，是为了让侧栏计数能反映“搜索结果”而不是“分类之后的结果”——
 * 否则切到“图片”分类后，侧栏其他分类的计数会全部变成 0，用户就看不出还能切到哪里去了。
 *
 * <p>另外三件容易被忽略的事：
 * <ol>
 *   <li><b>过期结果丢弃</b>：连续刷新/换文件夹时先发起的扫描可能后返回，用代次号保证只渲染最新一次。</li>
 *   <li><b>隐藏内容延后统计</b>：被剪枝目录的内部规模在表格显示之后才算，不拖慢首屏。</li>
 *   <li><b>取消不丢结果</b>：取消后展示已扫到的部分，而不是清空界面。</li>
 * </ol>
 */
public class MainWindow {

    private final Stage stage;
    private final BorderPane rootPane = new BorderPane();
    private final StackPane centerStack = new StackPane();
    private final LoadingView loadingView = new LoadingView();
    private final Sidebar sidebar = new Sidebar();
    private final SearchBar searchBar = new SearchBar();
    private final RecentStrip recentStrip = new RecentStrip();
    /** 在构造函数里创建：它需要拿到已完成初始化的存储层。 */
    private final FileTable table;
    /** 保留引用，以便自检能走"点击星标"的同一条代码路径。 */
    private final Badges badges;

    /** 名称列左侧图标的来源：图片缩略图 / 系统真实图标（M8）。 */
    private final FileIcons icons;

    private final Label rootLabel = new Label();
    private final Label statusLabel = new Label();
    private final Button detailsButton = new Button("详情…");
    private final Button chooseButton = new Button("切换文件夹");
    private final Button refreshButton = new Button("刷新");
    private final Button settingsButton = new Button("设置");
    private final Button createButton = new Button("批量创建");
    private final Button undoCreateButton = new Button("撤销创建");
    private final Button batchRenameButton = new Button("批量重命名");
    private final Button undoRenameButton = new Button("撤销重命名");
    private final Button themeButton = new Button("深色");
    private final CheckBox showFolders = new CheckBox("显示文件夹");

    private final ObservableList<FileItem> allItems = FXCollections.observableArrayList();
    private final FilteredList<FileItem> searchFiltered;
    private final FilteredList<FileItem> categoryFiltered;
    private final SortedList<FileItem> sortedItems;

    /**
     * 排除规则。
     *
     * <p>不是 final 且在 {@link #prepareStores(Path)} 里按根目录重建：程序自身的
     * {@code app\} 与 {@code runtime\} 只能按精确路径排除，而精确路径取决于根目录。
     */
    private Exclusions exclusions = Exclusions.defaults();
    /**
     * 删除策略。
     *
     * <p>不是 final：用户可以在设置里改阈值或勾选"一律走回收站"。改完通过
     * {@link #applyDeletePolicy(long, boolean)} 同时更新这里与 {@code deleteService} 持有的那一份。
     */
    private DeletePolicy deletePolicy = new DeletePolicy();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicLong generation = new AtomicLong(0);
    private final AtomicLong progressEvents = new AtomicLong(0);
    /**
     * 后台扫描完成的次数。
     *
     * <p>存在的理由：自从有了索引缓存，"{@code lastResult != null}" 就不再等于"扫描已完成"——
     * 缓存在构造函数里就把 lastResult 填上了。任何拿它当完成信号的地方（自检、截图）
     * 都会在扫描还没跑完时就继续往下走，读到半成品状态。这个计数器才是真正的完成信号。
     */
    private final AtomicLong scanCompletions = new AtomicLong(0);
    private final ExecutorService workers = Executors.newFixedThreadPool(2, runnable -> {
        Thread t = new Thread(runnable, "filepanel-worker");
        t.setDaemon(true);
        return t;
    });

    private Path root;
    private String rootReason = "";
    private ScanResult lastResult;
    private HiddenStats hiddenStats;
    private SearchQuery query = SearchQuery.EMPTY;
    private long lastFilterMillis;
    private ConfigStore configStore;
    private DeleteService deleteService;
    private BatchRenameService batchRenameService;
    /** 批量创建的执行层与撤销日志（M10）。 */
    private CreateService createService;
    private CreateJournal createJournal;
    /** 创建进度提示，非空时显示在状态栏（和内容索引提示同一个套路）。 */
    private String createProgressHint;
    /** 上一次用的创建数量，连续批量创建时继承，免得每次都要重填。 */
    private int lastCreateCount = 10;
    private ContentIndex contentIndex;
    private ContentIndexer contentIndexer;
    /** 内容索引的进度提示，非空时显示在状态栏。 */
    private String contentIndexHint;
    /** 摘要列是否是被"内容搜索"自动打开的（清理查询时要自动收回）。 */
    private boolean snippetAutoShown;
    /** 上一次用过的批量重命名规则，仅在同一会话内记住。 */
    private RenameRules lastRenameRules;

    /**
     * 机器级状态（"上次管理的目录"）。绿色版不需要它，安装版必须靠它记住根目录。
     *
     * <p>由 {@link MainApp} 在构造之后注入；注入时立刻记一次当前根目录，
     * 否则"首次选择文件夹"的那一次不会被记住，用户第二次打开还得再选一遍。
     */
    private MachineState machineState;
    private RecentStore recentStore;
    private FavoriteStore favoriteStore;
    private TagStore tagStore;
    private IndexCache indexCache;
    /** 当前主题：light / dark。 */
    private String currentTheme = Themes.LIGHT;

    /** 本次启动是否用缓存先把列表画了出来（用于状态栏说明"正在后台刷新"）。 */
    private boolean showingCachedResult;
    /** 本次启动是否成功用上了索引缓存。扫描完成后<b>不</b>复位，供自检判断"秒开是否生效"。 */
    private boolean cacheUsedAtStartup;
    /** 从构造窗口到用缓存把列表画出来所花的时间（毫秒）。 */
    private long cacheLoadMillis = -1;
    /** 侧栏分类顺序，由 {@link #rebuildCategories()} 写入，供自检断言。 */
    private List<String> categoryIds = List.of();

    /**
     * 基于当前内存索引的活计数。
     *
     * <p>不能用 {@link ScanResult#fileCount()} 显示"共 N 个文件"：那是扫描那一刻的快照，
     * 删除或重命名之后就会开始说谎（删了文件数量却不变）。凡是被界面改动过的数据，
     * 计数必须从当前索引重新得出。
     */
    private long liveFileCount;
    private long liveDirCount;
    private long liveBytes;
    /** 一次性操作反馈（如"已复制路径"），几秒后自动消失。 */
    private String transientHint;
    private javafx.animation.PauseTransition hintTimer;

    /**
     * 注入机器级状态。**注入时立刻记一次当前根目录**：
     * {@link #prepareStores(Path)} 在构造期就跑过了，而注入发生在构造之后，
     * 不在这里补一次的话"首次选择文件夹"那一次不会被记住。
     */
    public void setMachineState(MachineState state) {
        this.machineState = state;
        if (state != null && root != null) {
            state.rememberRoot(root, MainApp.APP_VERSION);
        }
    }

    /** 供自检使用：当前记住的"上次管理的目录"。 */
    public String rememberedRootForSelfTest() {
        if (machineState == null) {
            return "（未接入机器级状态）";
        }
        Path last = machineState.lastRoot();
        return last == null ? "（没有记住任何目录）" : last.toString();
    }

    /** 供自检使用：机器级状态文件的落点。 */
    public String machineStateLocationForSelfTest() {
        return machineState == null ? "（未接入）" : machineState.file().toString();
    }

    public MainWindow(Stage stage, RootResolver.Resolved resolved) {
        this.stage = stage;
        this.root = resolved.root();
        this.rootReason = resolved.reason();

        // 顺序很重要：存储层必须先就绪，表格的星标列与标签列一渲染就要向它查询
        prepareStores(this.root);
        this.badges = new Badges();
        this.icons = new FileIcons();
        this.table = new FileTable(badges);
        this.table.setIcons(icons);

        searchFiltered = new FilteredList<>(allItems, item -> true);
        categoryFiltered = new FilteredList<>(searchFiltered, item -> true);
        sortedItems = new SortedList<>(categoryFiltered);
        // 必须把 SortedList 的比较器绑定到 TableView 的比较器上，排序才会真正生效。
        // 不绑定的话 JavaFX 只在表头画一个“已排序”的箭头，数据顺序完全不变——
        // 数据和界面都“看起来正常”，只有核对顺序才能发现。
        sortedItems.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sortedItems);

        buildLayout();
        wireActions();
        restoreUiState();
        updateRootLabel();
        showOnlyLoading();
        refreshRecentStrip();
    }

    /** 为某个根目录准备好全部存储。换根目录时必须重新调用，否则会把 A 文件夹的数据写到 B 里。 */
    private void prepareStores(Path forRoot) {
        Path dataDir = forRoot.resolve(ConfigStore.DIR_NAME);
        exclusions = Exclusions.forRoot(forRoot);
        configStore = new ConfigStore(forRoot);
        // 记住"上次管理的目录"：安装版的根目录下次要靠它恢复（绿色版用不到，但记着无害）
        if (machineState != null) {
            machineState.rememberRoot(forRoot, MainApp.APP_VERSION);
        }        deleteService = new DeleteService(deletePolicy, new DeleteJournal(dataDir));
        batchRenameService = new BatchRenameService(new RenameJournal(dataDir));
        createService = new CreateService();
        createJournal = new CreateJournal(dataDir);
        contentIndex = new ContentIndex(dataDir);
        contentIndexer = new ContentIndexer(contentIndex);
        recentStore = new RecentStore(dataDir);
        favoriteStore = new FavoriteStore(dataDir);
        tagStore = new TagStore(dataDir);
        indexCache = new IndexCache(dataDir);
    }

    // ------------------------------------------------------------------ 布局

    private void buildLayout() {
        HBox header = new HBox(10);
        header.getStyleClass().add("toolbar");
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(10, 14, 10, 14));

        rootLabel.getStyleClass().add("root-label");
        HBox.setHgrow(rootLabel, Priority.ALWAYS);
        rootLabel.setMaxWidth(Double.MAX_VALUE);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        header.getChildren().addAll(rootLabel, spacer, showFolders, themeButton,
                createButton, undoCreateButton, batchRenameButton, undoRenameButton,
                chooseButton, settingsButton, refreshButton);

        centerStack.getChildren().setAll(loadingView);

        HBox status = new HBox(10);
        status.getStyleClass().add("status-bar");
        status.setAlignment(Pos.CENTER_LEFT);
        status.setPadding(new Insets(6, 14, 6, 14));
        statusLabel.getStyleClass().add("status-label");
        Region statusSpacer = new Region();
        HBox.setHgrow(statusSpacer, Priority.ALWAYS);
        status.getChildren().addAll(statusLabel, statusSpacer, detailsButton);

        rootPane.setTop(new VBox(header, searchBar, recentStrip));
        rootPane.setLeft(sidebar);
        rootPane.setCenter(centerStack);
        rootPane.setBottom(status);
        rootPane.getStyleClass().add("main-window");
    }

    private void wireActions() {
        loadingView.setOnCancel(() -> {
            cancelled.set(true);
            loadingView.markCancelled();
        });

        refreshButton.setOnAction(e -> scan(root));

        chooseButton.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("选择要管理的文件夹");
            if (root != null && Files.isDirectory(root)) {
                chooser.setInitialDirectory(root.toFile());
            }
            File chosen = chooser.showDialog(stage);
            if (chosen != null) {
                scan(chosen.toPath());
            }
        });

        showFolders.selectedProperty().addListener((obs, was, is) -> refilter());
        searchBar.setOnQueryChanged(this::applyQueryText);
        sidebar.setOnSelectionChanged(this::applyCategorySelection);
        detailsButton.setOnAction(e -> showDetails());
        detailsButton.setDisable(true);

        recentStrip.setOnOpen(this::openRecentEntry);
        recentStrip.setOnCollapsedChanged(this::persistUiState);

        batchRenameButton.setOnAction(e -> batchRenameSelected());
        undoRenameButton.setOnAction(e -> undoBatchRename());
        refreshUndoButton();

        themeButton.setOnAction(e -> toggleTheme());

        settingsButton.setOnAction(e -> openSettings());

        createButton.setOnAction(e -> batchCreate());
        undoCreateButton.setOnAction(e -> undoBatchCreate());
        refreshUndoCreateButton();

        wireTableInteractions();
    }

    // -------------------------------------------------------------- 批量创建

    /** 打开批量创建对话框；用户确认后在后台线程执行。 */
    private void batchCreate() {
        Optional<BatchCreateDialog.Result> choice =
                BatchCreateDialog.show(stage, root, lastCreateCount);
        if (choice.isEmpty()) {
            return;
        }
        BatchCreateDialog.Result confirmed = choice.get();
        lastCreateCount = confirmed.rules().count;

        BatchCreatePlan plan = confirmed.plan();
        if (plan.toCreateCount() == 0) {
            setTransientHint("没有可创建的项目：" + plan.summary());
            return;
        }

        createButton.setDisable(true);
        createProgressHint = "正在创建 0/" + plan.toCreateCount() + "…";
        updateStatus();

        // 必须放后台：实测每条约 0.5 ms，五万条就是几十秒。
        // 放在 JavaFX 线程上等于几十秒界面假死——删除那边已经暴露过同类问题，不能再犯。
        workers.submit(() -> {
            CreateService.Outcome outcome = createService.execute(plan, confirmed.rules(),
                    createJournal, (done, total, name) -> {
                        if (done % 20 == 0 || done == total) {
                            Platform.runLater(() -> {
                                createProgressHint = "正在创建 " + done + "/" + total + "…（"
                                        + name + "）";
                                updateStatus();
                            });
                        }
                    });
            Platform.runLater(() -> onCreateFinished(outcome, confirmed.rules()));
        });
    }

    /** 创建结束后：刷新列表、选中新建项、报告结果并恢复按钮。 */
    private void onCreateFinished(CreateService.Outcome outcome, CreateRules rules) {
        createButton.setDisable(false);
        createProgressHint = null;
        refreshUndoCreateButton();

        if (!outcome.created().isEmpty()) {
            // 新建之后必须重新扫描：不刷新的话列表里根本没有这些东西，
            // 用户会以为创建失败（"撤销创建"也就无从下手）
            scan(root);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("已创建 ").append(outcome.createdCount()).append(" 个").append(rules.target.label());
        if (outcome.skipped() > 0) {
            sb.append("，跳过 ").append(outcome.skipped()).append(" 个（同名或名字不可用）");
        }
        if (outcome.hasFailures()) {
            sb.append("，").append(outcome.failures().size()).append(" 个失败");
            // 失败要能看见原因，不能只报个数字
            for (CreateService.Failure failure : outcome.failures().stream().limit(3).toList()) {
                sb.append("；").append(failure.path().getFileName()).append("：")
                        .append(failure.message());
            }
        }
        if (outcome.failures().isEmpty() && outcome.createdCount() > 0) {
            sb.append("　可点「撤销创建」整体收回");
        }
        setTransientHint(sb.toString());
    }

    /**
     * 撤销上一次批量创建：把当时建出来的东西删掉。
     *
     * <p>走回收站而不是永久删除——批量创建出来的东西用户未必都想要，
     * 但"撤销"这种操作本身不该是不可恢复的。删文件夹会连内容一起进回收站，
     * 而这里新建出来的文件夹本来就是空的。
     */
    private void undoBatchCreate() {
        Optional<CreateJournal.Batch> last = createJournal.last();
        if (last.isEmpty()) {
            setTransientHint("没有可撤销的批量创建");
            return;
        }
        CreateJournal.Batch batch = last.get();
        List<FileItem> items = new ArrayList<>();
        for (Path path : batch.pathList()) {
            FileItem item = itemForPath(path);
            if (item != null) {
                items.add(item);
            }
        }
        if (items.isEmpty()) {
            // 东西已经不在了（用户自己删了，或移走了）：把这条失效记录清掉，
            // 否则"撤销创建"会一直挂着一个点了没反应的入口
            createJournal.removeBatch(batch.id);
            refreshUndoCreateButton();
            setTransientHint("这批创建出来的东西已经不在了，已清除相应记录");
            return;
        }

        DeleteService.Outcome outcome = deleteService.execute(items, DeleteService.Mode.ALL_RECYCLE);
        createJournal.removeBatch(batch.id);
        refreshUndoCreateButton();
        applyDeleteOutcome(outcome);

        StringBuilder sb = new StringBuilder();
        sb.append("已撤销：收回 ").append(outcome.recycled().size()).append(" 项到回收站");
        if (outcome.hasFailures()) {
            sb.append("，").append(outcome.failures().size()).append(" 项失败");
        }
        setTransientHint(sb.toString());
    }

    /** 由磁盘上的路径构造一个条目（撤销时需要，那些路径未必还在当前列表里）。 */
    private static FileItem itemForPath(Path path) {
        try {
            if (!Files.exists(path)) {
                return null;
            }
            boolean directory = Files.isDirectory(path);
            String name = path.getFileName() == null ? "" : path.getFileName().toString();
            String ext = directory ? "" : FileItem.extensionOf(name);
            return new FileItem(name, path, name, "", directory, ext,
                    directory ? FileKind.DIRECTORY : FileKind.ofExtension(ext),
                    directory ? 0 : Files.size(path), null, null, null, 0, false, false, false);
        } catch (Exception e) {
            return null;
        }
    }

    /** 撤销创建的按钮是否可用，取决于日志里有没有记录。 */
    private void refreshUndoCreateButton() {
        boolean available = createJournal != null && createJournal.last().isPresent();
        undoCreateButton.setDisable(!available);
        undoCreateButton.setTooltip(new Tooltip(available
                ? createJournal.describeLast() : "没有可撤销的批量创建"));
    }

    /**
     * 供自检使用：批量创建的界面接线断言。
     *
     * <p>刻意<b>不</b>在用户的文件夹里真的创建任何东西：自检跑的是用户真实的目录，
     * 建一堆测试文件是不可接受的。真正"建出来"的行为由
     * {@code CreateRulesTest} 在临时目录里覆盖；这里只用临时目录验对话框的逻辑。
     */
    public String batchCreateSelfCheck(List<String> failures) {
        StringBuilder sb = new StringBuilder();
        sb.append("    工具栏「批量创建」入口：").append(hasToolbarButton("批量创建"))
                .append("　「撤销创建」入口：").append(hasToolbarButton("撤销创建"))
                .append("　撤销当前可用=").append(!undoCreateButton.isDisabled()).append('\n');
        if (!hasToolbarButton("批量创建")) {
            failures.add("工具栏上没有「批量创建」按钮");
        }
        if (!hasToolbarButton("撤销创建")) {
            failures.add("工具栏上没有「撤销创建」按钮");
        }

        Path scratch = null;
        try {
            scratch = Files.createTempDirectory("filepanel-create-selftest");
            sb.append(com.zean.filepanel.ui.dialog.BatchCreateDialog.selfCheck(scratch, failures));
        } catch (IOException e) {
            failures.add("批量创建自检无法准备临时目录：" + e);
        } finally {
            if (scratch != null) {
                try (java.util.stream.Stream<Path> walk = Files.walk(scratch)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // 临时目录清理失败不影响结论
                        }
                    });
                } catch (IOException ignored) {
                    // 同上
                }
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 设置

    /**
     * 把配置里的范围字符串解成枚举。
     *
     * <p>宽容匹配：配置文件可能被手工改成小写、或者留着旧版本的值。
     * 认不出来就退回 {@code ALL}——范围搞错了只会让人搜不到东西，
     * 而退回默认至少和"以前的行为"一致。
     */
    private static SearchScope parseScope(String value) {
        if (value == null || value.isBlank()) {
            return SearchScope.ALL;
        }
        for (SearchScope scope : SearchScope.values()) {
            if (scope.name().equalsIgnoreCase(value.trim())) {
                return scope;
            }
        }
        return SearchScope.ALL;
    }

    /** 打开设置对话框，并把用户确认的结果立即生效 + 落盘。 */
    private void openSettings() {
        Optional<SettingsDialog.Result> result = SettingsDialog.show(stage, deletePolicy);
        if (result.isEmpty()) {
            return;
        }
        SettingsDialog.Result confirmed = result.get();
        applyDeletePolicy(confirmed.thresholdBytes(), confirmed.alwaysRecycle());
        persistUiState();
        // 改完立刻说一句"现在是什么规则"：这个设置影响的是不可恢复的操作，
        // 不能让用户保存完还要自己去别处确认到底生效了没有
        setTransientHint("删除策略已更新：" + deletePolicy.describe());
    }

    /**
     * 应用删除策略。
     *
     * <p>必须同时更新两处：{@code deletePolicy}（界面的确认框、详情、状态栏都读它）与
     * {@code deleteService}（真正执行删除时读它持有的那一份）。只改一处会出现
     * "确认框说会进回收站、实际却永久删除"这种最不能接受的偏差。
     */
    private void applyDeletePolicy(long thresholdBytes, boolean alwaysRecycle) {
        DeletePolicy next = new DeletePolicy(thresholdBytes, alwaysRecycle);
        this.deletePolicy = next;
        if (deleteService != null) {
            deleteService.setPolicy(next);
        }
    }

    // ------------------------------------------------------------------ 主题

    /** 切换亮/暗主题并立即落盘（用户按一下就该记住，不必等到关窗口）。 */
    private void toggleTheme() {
        applyTheme(Themes.toggle(currentTheme));
        persistUiState();
    }

    /** 供自检/截图使用：直接指定主题。 */
    public void setTheme(String theme) {
        applyTheme(theme);
    }

    /** 供自检使用：当前主题。 */
    public String currentTheme() {
        return currentTheme;
    }

    private void applyTheme(String theme) {
        currentTheme = Themes.isDark(theme) ? Themes.DARK : Themes.LIGHT;
        Themes.apply(rootPane, currentTheme);
        themeButton.setText(Themes.toggleLabel(currentTheme));
    }

    // -------------------------------------------------------------- 批量重命名

    /** 打开批量重命名对话框并执行用户确认的方案。 */
    private void batchRenameSelected() {
        List<FileItem> selected = selectedItems();
        if (selected.isEmpty()) {
            setTransientHint("请先选中要重命名的文件（按住 Ctrl 或 Shift 可多选）");
            return;
        }
        Optional<BatchRenameDialog.Result> input =
                BatchRenameDialog.show(stage, selected, lastRenameRules);
        if (input.isEmpty()) {
            return;
        }
        BatchRenameDialog.Result result = input.get();
        // 记住本次规则，同一会话内再次打开时不必重填
        lastRenameRules = result.rules();

        BatchRenameService.Outcome outcome =
                batchRenameService.rename(result.entries(), result.description());
        applyRenameOutcome(outcome);
    }

    /**
     * 撤销最近一次批量重命名。
     *
     * <p>撤销是"反向跑一遍同样的两阶段流程"，因此它同样可能改变目录结构，
     * 所以这里直接重新扫描而不是逐个改内存里的路径——撤销是低频操作，
     * 用一次扫描换"绝对正确"是划算的。
     */
    private void undoBatchRename() {
        if (batchRenameService == null) {
            return;
        }
        Optional<BatchRenameService.Outcome> undone = batchRenameService.undoLast();
        if (undone.isEmpty()) {
            setTransientHint("没有可撤销的批量重命名");
            refreshUndoButton();
            return;
        }
        BatchRenameService.Outcome outcome = undone.get();
        if (!outcome.success()) {
            showError("撤销未完全成功", String.join("\n", outcome.problems())
                    + "\n\n请到文件所在位置确认这些文件的当前名字。");
        } else {
            remapStoresFor(outcome);
            setTransientHint("已撤销，还原 " + outcome.succeededCount() + " 项");
            scan(root);
        }
        refreshUndoButton();
    }

    /** 把执行结果落到内存索引与各个存储上。 */
    private void applyRenameOutcome(BatchRenameService.Outcome outcome) {
        if (!outcome.success()) {
            showError("批量重命名未完成", String.join("\n", outcome.problems())
                    + "\n\n已尽力把文件还原到操作前的名字，请确认一下。");
            refreshUndoButton();
            return;
        }
        remapStoresFor(outcome);

        boolean touchedDirectory = outcome.results().stream().anyMatch(r ->
                r.ok() && Files.isDirectory(r.to()));
        if (touchedDirectory) {
            // 目录改名会让它内部所有条目的路径失效。逐个改内存里的路径容易漏，
            // 而这种规模的重扫描只要几毫秒，所以直接用扫描换正确性。
            setTransientHint("已重命名 " + outcome.succeededCount() + " 项（含文件夹），正在刷新列表…");
            scan(root);
        } else {
            applyRenamesInPlace(outcome.results());
            setTransientHint("已重命名 " + outcome.succeededCount() + " 项");
        }
        refreshUndoButton();
    }

    /** 按执行结果迁移最近使用 / 收藏 / 标签里的路径。 */
    private void remapStoresFor(BatchRenameService.Outcome outcome) {
        for (BatchRenameService.MoveResult result : outcome.results()) {
            if (!result.ok()) {
                continue;
            }
            recentStore.remapPath(result.from(), result.to());
            favoriteStore.remapPath(result.from(), result.to());
            tagStore.remapPath(result.from(), result.to());
        }
        refreshRecentStrip();
    }

    /** 文件（非目录）改名后逐个替换内存索引里的条目。 */
    private void applyRenamesInPlace(List<BatchRenameService.MoveResult> results) {
        for (BatchRenameService.MoveResult result : results) {
            if (!result.ok()) {
                continue;
            }
            for (int i = 0; i < allItems.size(); i++) {
                if (allItems.get(i).path().equals(result.from())) {
                    allItems.set(i, renamedItem(allItems.get(i), result.to()));
                    break;
                }
            }
        }
        afterMutation();
    }

    private void refreshUndoButton() {
        boolean canUndo = batchRenameService != null && batchRenameService.hasUndoable();
        undoRenameButton.setDisable(!canUndo);
        undoRenameButton.setTooltip(new Tooltip(batchRenameService == null
                ? "没有可撤销的重命名" : batchRenameService.describeUndoable()));
    }

    // -------------------------------------------------------- 收藏与标签视图

    /**
     * 表格向存储层查询收藏与标签的入口。
     *
     * <p>做成内部类而不是让表格直接持有 store：表格只需要"问"，不需要知道数据存在哪里，
     * 也就没有机会绕过 store 的路径归一化与迁移逻辑。
     */
    private final class Badges implements ItemBadges {

        @Override
        public boolean isFavorite(FileItem item) {
            return item != null && favoriteStore.isFavorite(item.path());
        }

        @Override
        public List<String> tagsOf(FileItem item) {
            return item == null ? List.of() : tagStore.tagsOf(item.path());
        }

        @Override
        public void toggleFavorite(FileItem item) {
            if (item == null) {
                return;
            }
            boolean nowFavorite = favoriteStore.toggle(item.path());
            recentStore.record(item.path(), nowFavorite
                    ? RecentStore.Op.FAVORITE : RecentStore.Op.TAG, item.directory(), item.size());
            setTransientHint(nowFavorite
                    ? "已收藏「" + item.name() + "」"
                    : "已取消收藏「" + item.name() + "」");
            refreshRecentStrip();
            rebuildCategories();
            refilter();
        }
    }

    /** 刷新"最近使用"卡片区，并顺手清理指向已不存在文件的记录。 */
    private void refreshRecentStrip() {
        recentStore.pruneMissing();
        recentStrip.setEntries(recentStore.recent(RecentStrip.MAX_CARDS));
    }

    private void openRecentEntry(RecentStore.Entry entry) {
        Path path = entry.pathValue();
        if (!Files.exists(path)) {
            // 记录还在，文件没了：明确告诉用户，而不是点了没反应
            alertInfo("文件已不存在", "「" + entry.name + "」已不在原位置：\n" + entry.path
                    + "\n\n可以从“最近使用”中移除这条记录。");
            return;
        }
        try {
            OpenService.open(path);
            recentStore.record(path, RecentStore.Op.OPEN, entry.directory, entry.size);
            refreshRecentStrip();
        } catch (IOException e) {
            showError("打开失败", e.getMessage());
        }
    }

    /** 给选中项打标签。 */
    private void tagSelected() {
        List<FileItem> selected = selectedItems();
        if (selected.isEmpty()) {
            return;
        }
        Optional<TagDialog.Result> input = TagDialog.show(stage, selected, tagStore);
        if (input.isEmpty()) {
            return;
        }
        TagDialog.Result result = input.get();
        int added = 0;
        for (FileItem item : selected) {
            for (String tag : result.tagsToAdd()) {
                if (tagStore.addTag(item.path(), tag)) {
                    added++;
                }
            }
            for (String tag : result.tagsToRemove()) {
                tagStore.removeTag(item.path(), tag);
            }
            recentStore.record(item.path(), RecentStore.Op.TAG, item.directory(), item.size());
        }
        table.refresh();
        refreshRecentStrip();
        rebuildCategories();
        refilter();
        setTransientHint(selected.size() == 1
                ? "已更新「" + selected.get(0).name() + "」的标签"
                : "已为 " + selected.size() + " 项添加 " + added + " 个标签");
    }

    // ------------------------------------------------------------ 表格交互

    private void wireTableInteractions() {
        // 双击打开，与资源管理器一致
        table.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2
                    && table.getSelectionModel().getSelectedItem() != null) {
                openSelected();
            }
        });
        table.setContextMenu(buildRowMenu());

        // 与选中项相关的快捷键挂在表格上：挂在根节点会让 F2、Delete 在搜索框里也生效，
        // 那样用户就没法在输入框里按 Delete 删字符了。
        table.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            switch (event.getCode()) {
                case ENTER -> {
                    openSelected();
                    event.consume();
                }
                case F2 -> {
                    renameSelected();
                    event.consume();
                }
                case DELETE -> {
                    deleteSelected();
                    event.consume();
                }
                case C -> {
                    if (event.isControlDown()) {
                        copyPathSelected();
                        event.consume();
                    }
                }
                default -> {
                    // 其余按键交给默认处理
                }
            }
        });

        // 全局快捷键挂在根节点上：F5、Ctrl+F、Esc 在焦点位于搜索框时也应该可用
        rootPane.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            switch (event.getCode()) {
                case F5 -> {
                    scan(root);
                    event.consume();
                }
                case F -> {
                    if (event.isControlDown()) {
                        searchBar.focusSearch();
                        event.consume();
                    }
                }
                case ESCAPE -> {
                    searchBar.clear();
                    event.consume();
                }
                default -> {
                    // 其余按键交给默认处理
                }
            }
        });
    }

    private ContextMenu buildRowMenu() {
        MenuItem open = new MenuItem("打开");
        open.setOnAction(e -> openSelected());
        MenuItem reveal = new MenuItem("打开所在文件夹");
        reveal.setOnAction(e -> revealSelected());
        MenuItem copyPath = new MenuItem("复制路径");
        copyPath.setOnAction(e -> copyPathSelected());
        MenuItem rename = new MenuItem("重命名");
        rename.setOnAction(e -> renameSelected());
        MenuItem batch = new MenuItem("批量重命名…");
        batch.setOnAction(e -> batchRenameSelected());
        MenuItem tags = new MenuItem("标签…");
        tags.setOnAction(e -> tagSelected());
        MenuItem favorite = new MenuItem("加入/取消收藏");
        favorite.setOnAction(e -> toggleFavoriteSelected());
        MenuItem delete = new MenuItem("删除…");
        delete.setOnAction(e -> deleteSelected());
        MenuItem refresh = new MenuItem("刷新");
        refresh.setOnAction(e -> scan(root));

        // 没有选中项时禁用与选中项相关的动作，避免点了没反应还不知道为什么
        javafx.beans.binding.BooleanBinding noSelection =
                table.getSelectionModel().selectedItemProperty().isNull();
        open.disableProperty().bind(noSelection);
        reveal.disableProperty().bind(noSelection);
        copyPath.disableProperty().bind(noSelection);
        rename.disableProperty().bind(noSelection);
        batch.disableProperty().bind(noSelection);
        tags.disableProperty().bind(noSelection);
        favorite.disableProperty().bind(noSelection);
        delete.disableProperty().bind(noSelection);

        return new ContextMenu(open, reveal, new SeparatorMenuItem(),
                rename, batch, tags, favorite, copyPath, new SeparatorMenuItem(), delete, refresh);
    }

    /** 切换选中项的收藏状态（全部选中项按第一项的状态取反）。 */
    private void toggleFavoriteSelected() {
        List<FileItem> selected = selectedItems();
        if (selected.isEmpty()) {
            return;
        }
        boolean target = !favoriteStore.isFavorite(selected.get(0).path());
        for (FileItem item : selected) {
            favoriteStore.setFavorite(item.path(), target);
            recentStore.record(item.path(), RecentStore.Op.FAVORITE, item.directory(), item.size());
        }
        table.refresh();
        refreshRecentStrip();
        rebuildCategories();
        refilter();
        setTransientHint(target
                ? "已收藏 " + selected.size() + " 项"
                : "已取消收藏 " + selected.size() + " 项");
    }

    /** 供测试与自检使用：当前选中的条目。 */
    public List<FileItem> selectedItems() {
        return List.copyOf(table.getSelectionModel().getSelectedItems());
    }

    // -------------------------------------------------------------- 文件操作

    private void openSelected() {
        FileItem item = table.getSelectionModel().getSelectedItem();
        if (item == null) {
            return;
        }
        try {
            OpenService.open(item.path());
            recordRecent(item, RecentStore.Op.OPEN);
        } catch (IOException e) {
            showError("打开失败", e.getMessage());
        }
    }

    private void revealSelected() {
        FileItem item = table.getSelectionModel().getSelectedItem();
        if (item == null) {
            return;
        }
        try {
            OpenService.revealInExplorer(item.path());
            recordRecent(item, RecentStore.Op.REVEAL);
        } catch (IOException e) {
            showError("打开所在文件夹失败", e.getMessage());
        }
    }

    private void copyPathSelected() {
        List<FileItem> selected = selectedItems();
        if (selected.isEmpty()) {
            return;
        }
        if (selected.size() == 1) {
            Clipboards.copyText(OpenService.pathText(selected.get(0).path()));
            setTransientHint("已复制路径");
        } else {
            Clipboards.copyLines(selected.stream()
                    .map(item -> OpenService.pathText(item.path())).toList());
            setTransientHint("已复制 " + selected.size() + " 个路径");
        }
        selected.forEach(item -> recordRecent(item, RecentStore.Op.COPY_PATH));
    }

    /** 记录一次"最近使用"，并刷新卡片区。 */
    private void recordRecent(FileItem item, RecentStore.Op op) {
        if (item == null) {
            return;
        }
        recentStore.record(item.path(), op, item.directory(), item.size());
        refreshRecentStrip();
    }

    private void renameSelected() {
        FileItem item = table.getSelectionModel().getSelectedItem();
        if (item == null) {
            return;
        }
        Optional<String> input = RenameDialog.show(stage, item);
        if (input.isEmpty()) {
            return;
        }
        String newName = input.get();
        if (newName.equals(item.name())) {
            return;
        }

        RenameService.Result result = RenameService.rename(item.path(), newName);
        if (!result.success()) {
            showError("重命名失败", result.error());
            return;
        }
        // 改名后必须把"最近使用 / 收藏 / 标签"里的路径一起迁移，
        // 否则用户会看到"刚收藏的文件改个名就从收藏里消失了"——数据没丢，是键没跟上
        Path oldPath = item.path();
        Path newPath = result.newPath();
        recentStore.remapPath(oldPath, newPath);
        favoriteStore.remapPath(oldPath, newPath);
        tagStore.remapPath(oldPath, newPath);

        replaceAfterRename(item, newPath);
        recordRecentWithPath(newPath, item.directory(), item.size(), RecentStore.Op.RENAME);
        setTransientHint("已重命名为「" + newPath.getFileName() + "」"
                + (result.caseOnlyChange() ? "（仅大小写变化）" : ""));
    }

    /** 用路径直接记一次最近使用（重命名后旧 FileItem 已失效，不能再用它）。 */
    private void recordRecentWithPath(Path path, boolean directory, long size, RecentStore.Op op) {
        recentStore.record(path, op, directory, size);
        refreshRecentStrip();
    }

    /**
     * 用重命名后的新路径替换内存索引里的旧条目。
     *
     * <p>{@link FileItem} 是不可变 record，所以是"替换"而不是"修改"。
     * 路径相关的字段（名称、relPath、所在位置、层级、扩展名、类型）全部重算；
     * 大小与时间戳保持原值——Windows 上重命名<b>不会</b>改变文件的创建时间与修改时间。
     */
    private void replaceAfterRename(FileItem old, Path newPath) {
        int index = allItems.indexOf(old);
        if (index < 0) {
            return;
        }
        allItems.set(index, renamedItem(old, newPath));
        afterMutation();

        for (FileItem candidate : sortedItems) {
            if (candidate.path().equals(newPath)) {
                table.getSelectionModel().clearSelection();
                table.getSelectionModel().select(candidate);
                break;
            }
        }
    }

    /**
     * 用新路径构造一个替换用的条目。
     *
     * <p>{@link FileItem} 是不可变 record，所以是"替换"而不是"修改"。
     * 路径相关的字段（名称、relPath、所在位置、层级、扩展名、类型）全部重算；
     * 大小与时间戳保持原值——Windows 上重命名<b>不会</b>改变文件的创建时间与修改时间。
     */
    private FileItem renamedItem(FileItem old, Path newPath) {
        Path relative = root.relativize(newPath);
        Path parent = relative.getParent();
        String name = newPath.getFileName().toString();
        boolean directory = old.directory();
        String ext = directory ? "" : FileItem.extensionOf(name);

        return new FileItem(
                name, newPath,
                relative.toString().replace('\\', '/'),
                parent == null ? "" : parent.toString().replace('\\', '/'),
                directory, ext,
                directory ? FileKind.DIRECTORY : FileKind.ofExtension(ext),
                old.size(), old.created(), old.modified(), old.accessed(),
                Math.max(0, relative.getNameCount() - 1),
                old.hidden(), old.readOnly(), old.system());
    }

    private void deleteSelected() {
        List<FileItem> selected = selectedItems();
        if (selected.isEmpty()) {
            return;
        }
        Optional<DeleteConfirmDialog.Decision> decision =
                DeleteConfirmDialog.confirm(stage, selected, deletePolicy);
        if (decision.isEmpty()) {
            return;
        }

        DeleteConfirmDialog.Decision confirmed = decision.get();
        DeleteService.Outcome outcome = deleteService.execute(confirmed.items(), confirmed.mode());
        applyDeleteOutcome(outcome);
        reportDeleteOutcome(outcome);
    }

    private void applyDeleteOutcome(DeleteService.Outcome outcome) {
        // 文件没了，它在"最近使用 / 收藏 / 标签"里的记录也必须清掉，
        // 否则卡片上会一直挂着打不开的死链，标签侧栏里也会留下永远为 0 的分类
        List<FileItem> gone = new ArrayList<>(outcome.recycled());
        gone.addAll(outcome.deleted());
        for (FileItem item : gone) {
            recentStore.remove(item.path());
            favoriteStore.remove(item.path());
            tagStore.remove(item.path());
        }
        allItems.removeAll(outcome.recycled());
        allItems.removeAll(outcome.deleted());
        refreshRecentStrip();
        afterMutation();
    }

    private void reportDeleteOutcome(DeleteService.Outcome outcome) {
        StringBuilder sb = new StringBuilder();
        if (!outcome.recycled().isEmpty()) {
            sb.append(outcome.recycled().size()).append(" 项已放入回收站");
        }
        if (!outcome.deleted().isEmpty()) {
            if (sb.length() > 0) {
                sb.append("，");
            }
            sb.append(outcome.deleted().size()).append(" 项已永久删除");
        }
        if (outcome.hasFailures()) {
            if (sb.length() > 0) {
                sb.append("，");
            }
            sb.append(outcome.failures().size()).append(" 项失败");
        }
        if (outcome.hasUnverifiedRecycle()) {
            if (sb.length() > 0) {
                sb.append("，");
            }
            sb.append(outcome.recycleUnverified().size()).append(" 项未能确认进入回收站");
        }
        setTransientHint(sb.length() == 0 ? "没有项目被删除" : sb.toString());

        if (outcome.hasUnverifiedRecycle()) {
            warnUnverifiedRecycle(outcome.recycleUnverified());
        }
        if (outcome.hasFailures()) {
            offerPermanentFallback(outcome.failures());
        }
    }

    /**
     * 提示"报告成功送入回收站、但无法确认真的进了回收站"。
     *
     * <p>这种情况确实会发生：实测中 Shell 返回成功、文件也消失了，但回收站条目数没有增加，
     * 说明文件其实被永久删除。此时如果只按返回码告诉用户"可以还原"，就是在骗人。
     * 宁可让用户去回收站确认一下，也不能让他以为还有退路。
     */
    private void warnUnverifiedRecycle(List<FileItem> items) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.initOwner(stage);
        alert.setTitle("请确认回收站");
        alert.setHeaderText(items.size() + " 项已删除，但未能确认它们进入了回收站");
        alert.setContentText("""
                删除操作报告成功，且这些文件已从原位置消失，但回收站的条目数没有相应增加。
                这意味着它们很可能已被【永久删除】，无法从回收站还原。

                可能的原因：
                  · 系统关闭了回收站，或该磁盘的回收站配额不足；
                  · 文件位于网络位置，回收站不适用；
                  · 运行环境限制了写入回收站。

                建议到回收站里确认一下这几个文件是否还在。""");
        alert.getDialogPane().setPrefWidth(560);
        Themes.applyToDialog(alert.getDialogPane(), stage);
        alert.showAndWait();
    }

    /**
     * 回收站失败后的降级询问。
     *
     * <p>这里是 D4 里"绝不静默永久删除"的最后一道闸门：Shell 拒绝送入回收站时，
     * 我们只<b>报告</b>并询问，把是否永久删除的决定权交给用户。
     */
    private void offerPermanentFallback(List<DeleteService.Failure> failures) {
        String detail = failures.stream()
                .limit(8)
                .map(DeleteService.Failure::describe)
                .collect(java.util.stream.Collectors.joining("\n"));
        if (failures.size() > 8) {
            detail += "\n… 其余 " + (failures.size() - 8) + " 项略";
        }

        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.initOwner(stage);
        alert.setTitle("部分项目未能删除");
        alert.setHeaderText(failures.size() + " 项无法放入回收站");
        alert.setContentText("原因：\n" + detail
                + "\n\n是否改为永久删除？永久删除后无法从回收站恢复。");
        ButtonType permanent = new ButtonType("永久删除", ButtonBar.ButtonData.OK_DONE);
        alert.getButtonTypes().setAll(permanent, ButtonType.CANCEL);
        Themes.applyToDialog(alert.getDialogPane(), stage);

        Optional<ButtonType> choice = alert.showAndWait();
        if (choice.isPresent() && choice.get() == permanent) {
            DeleteService.Outcome second = deleteService.execute(
                    failures.stream().map(DeleteService.Failure::item).toList(),
                    DeleteService.Mode.ALL_PERMANENT);
            applyDeleteOutcome(second);
            setTransientHint(second.succeeded() + " 项已永久删除"
                    + (second.hasFailures() ? "，" + second.failures().size() + " 项仍然失败" : ""));
        }
        afterMutation();
    }

    /** 索引被改动后统一收口：重建分类、重算计数与统计、刷新状态栏。 */
    private void afterMutation() {
        rebuildCategories();
        refilter();
        updateStatus();
    }

    private void setTransientHint(String text) {
        this.transientHint = text;
        updateStatus();
        if (hintTimer == null) {
            hintTimer = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(5));
            hintTimer.setOnFinished(e -> {
                transientHint = null;
                updateStatus();
            });
        }
        hintTimer.playFromStart();
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(stage);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        applyStyles(alert);
        alert.showAndWait();
    }

    private void alertInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(stage);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        applyStyles(alert);
        alert.showAndWait();
    }

    private void applyStyles(Dialog<?> dialog) {
        Themes.applyToDialog(dialog.getDialogPane(), stage);
    }

    // ------------------------------------------------------------ 界面状态恢复

    /**
     * 恢复列宽，并处理"默认列宽改过"的迁移。
     *
     * <p><b>为什么必须抽成一个方法</b>：这段迁移原来只写在 {@link #restoreUiState()} 里，
     * 而"切换文件夹"（{@link #scan(Path)}）那条路直接调了 {@code table.applyColumns()}，
     * 把迁移绕过去了——于是"默认列宽改过之后老配置仍沿用旧宽度"这个已经修好的问题，
     * 在换目录时会原样复现。两条路走同一份逻辑，才不会再分叉。
     *
     * <p>迁移规则：保存时写入当前 {@code LAYOUT_VERSION}；读取时若版本落后，
     * 则只沿用列的显隐、宽度回到新默认值。以后改动任何默认列宽就把
     * {@code FileTable.LAYOUT_VERSION} 加一。
     */
    private void applyColumnsWithMigration(UiState state) {
        if (state.layoutVersion >= FileTable.LAYOUT_VERSION) {
            table.applyColumns(state.columns);
        } else {
            table.applyColumnVisibility(state.columns);
        }
    }

    private void restoreUiState() {
        UiState state = configStore.load();

        showFolders.setSelected(state.showFolders);
        applyColumnsWithMigration(state);
        recentStrip.setCollapsed(state.recentStripCollapsed, false);
        applyTheme(state.theme);
        // 必须在 prepareStores 之后（此时 deleteService 已存在）才能生效；
        // 构造函数里的调用顺序正是 prepareStores -> restoreUiState
        applyDeletePolicy(state.deleteThresholdBytes, state.alwaysRecycle);
        searchBar.setScope(parseScope(state.searchScope));

        if (state.sortColumnId != null) {
            table.applySort(state.sortColumnId, state.sortAscending);
        } else {
            table.applyDefaultSort();
        }

        if (stage != null) {
            if (state.windowWidth >= 640) {
                stage.setWidth(state.windowWidth);
            }
            if (state.windowHeight >= 400) {
                stage.setHeight(state.windowHeight);
            }
            if (!Double.isNaN(state.windowX) && !Double.isNaN(state.windowY)) {
                stage.setX(state.windowX);
                stage.setY(state.windowY);
            }
        }
    }

    private void persistUiState() {
        if (configStore == null) {
            return;
        }
        UiState state = new UiState();
        state.showFolders = showFolders.isSelected();
        state.sortColumnId = table.sortColumnId();
        state.sortAscending = table.sortAscending();
        state.columns = table.captureColumns();
        state.recentStripCollapsed = recentStrip.isCollapsed();
        state.theme = currentTheme;
        state.layoutVersion = FileTable.LAYOUT_VERSION;
        state.deleteThresholdBytes = deletePolicy.thresholdBytes();
        state.alwaysRecycle = deletePolicy.alwaysRecycle();
        state.searchScope = searchBar.scope().name();
        if (stage != null) {
            state.windowWidth = stage.getWidth();
            state.windowHeight = stage.getHeight();
            state.windowX = stage.getX();
            state.windowY = stage.getY();
        }
        configStore.save(state);
    }

    // ------------------------------------------------------------------ 过滤

    /** 搜索层谓词：关键词 + “显示文件夹”开关。 */
    private Predicate<FileItem> searchPredicate() {
        boolean folders = showFolders.isSelected();
        SearchQuery current = query;
        // 只有查询里真的写了 content: 才去查正文索引——普通搜索保持零额外开销
        boolean needsContent = current.needsContent();
        return item -> {
            if (!folders && item.directory()) {
                return false;
            }
            String content = needsContent ? contentIndex.textOf(item) : null;
            return current.matches(item,
                    tagStore.tagsOf(item.path()),
                    favoriteStore.isFavorite(item.path()),
                    content);
        };
    }

    /** 用户在搜索框输入后的入口（已由 SearchBar 防抖）。 */
    private void applyQueryText(String raw) {
        // 直接用搜索栏已经解析好的查询，而不是拿原文再 parse 一次：
        // 两处各解析一次、参数一旦不一致，就会出现"提示说按文件名筛、实际按路径搜"这类偏差。
        this.query = searchBar.currentQuery();
        if (query.needsContent()) {
            startContentIndexIfNeeded();
        }
        updateSnippetColumn();
        refilter();
    }

    /**
     * 按需建立内容索引。
     *
     * <p>不做成"每次扫描后自动建"：抽取正文是秒级开销，用户可能根本不用内容搜索。
     * 已索引且指纹没变的文件会被跳过，所以重复触发几乎不花时间。
     */
    private void startContentIndexIfNeeded() {
        if (contentIndexer == null || contentIndexer.isRunning()) {
            return;
        }
        contentIndex.pruneMissing();
        contentIndexHint = "正在建立内容索引…";
        updateStatus();
        contentIndexer.start(allItems, new ContentIndexer.Listener() {
            @Override
            public void onProgress(int done, int total, String current) {
                Platform.runLater(() -> {
                    contentIndexHint = "正在建立内容索引 " + done + "/" + total + "…（" + current + "）";
                    updateStatus();
                });
            }

            @Override
            public void onFinished(int indexed, int upToDate, int failed, boolean stopped) {
                Platform.runLater(() -> {
                    contentIndexHint = "内容索引完成：新索引 " + indexed + " 个文件"
                            + (upToDate > 0 ? "，已是最新 " + upToDate + " 个" : "")
                            + (failed > 0 ? "，无法读取正文 " + failed + " 个" : "")
                            + (stopped ? "（已达索引容量上限，其余文件未索引）" : "")
                            + "，共 " + FormatUtil.count(contentIndex.size()) + " 个文件可搜正文";
                    refilter();
                });
            }
        });
    }

    /** 内容搜索时自动显示摘要列，查询清空后再收回（只收回自己打开的情况）。 */
    private void updateSnippetColumn() {
        boolean needed = query.needsContent();
        if (needed) {
            if (!table.isColumnVisible(FileTable.COL_SNIPPET)) {
                table.setColumnVisible(FileTable.COL_SNIPPET, true);
                snippetAutoShown = true;
            }
            table.setSnippetProvider(this::snippetFor);
        } else {
            table.setSnippetProvider(null);
            if (snippetAutoShown) {
                table.setColumnVisible(FileTable.COL_SNIPPET, false);
                snippetAutoShown = false;
            }
        }
    }

    /** 取某个条目在当前内容查询下的命中摘要。 */
    private String snippetFor(FileItem item) {
        if (item == null || !query.needsContent()) {
            return "";
        }
        String content = contentIndex.textOf(item);
        if (content == null) {
            return "";
        }
        for (String term : query.contentTerms()) {
            String snippet = new SearchQuery.ContentTerm(term).snippet(content);
            if (!snippet.isEmpty()) {
                return snippet;
            }
        }
        return "";
    }

    private void applyCategorySelection() {
        SidebarCategory category = sidebar.selected();
        categoryFiltered.setPredicate(category::test);
        updateStatus();
    }

    /**
     * 重新计算搜索层与分类层。
     *
     * <p>刻意在设置完谓词后读一次 {@code size()} 把结果物化：
     * 这样测到的 {@link #lastFilterMillis} 是真实过滤成本，而不是“等会儿再说”的调度开销。
     * 状态栏会把它显示出来，因为“搜索够不够快”是能被用户直接感知的指标。
     */
    private void refilter() {
        long startedAt = System.nanoTime();
        searchFiltered.setPredicate(searchPredicate());
        categoryFiltered.setPredicate(sidebar.selected()::test);
        // 读一次 size() 强制把过滤结果物化，否则测到的只是“设置谓词”的开销而非真实过滤成本
        categoryFiltered.size();
        lastFilterMillis = Math.max(0, (System.nanoTime() - startedAt) / 1_000_000L);

        refreshLiveTotals();
        refreshSidebarCounts();
        updateStatus();
    }

    /**
     * 从当前内存索引重算总数、文件夹数与占用。
     *
     * <p>必须基于 {@code allItems} 而不是搜索结果：状态栏的"共 N 个文件"描述的是整个文件夹，
     * 而 {@code searchFiltered} 是搜索命中的子集。
     */
    private void refreshLiveTotals() {
        long files = 0;
        long dirs = 0;
        long bytes = 0;
        for (FileItem item : allItems) {
            if (item.directory()) {
                dirs++;
            } else {
                files++;
                bytes += item.size();
            }
        }
        liveFileCount = files;
        liveDirCount = dirs;
        liveBytes = bytes;
    }

    /**
     * 用<b>一趟遍历</b>算出侧栏所有计数。
     *
     * <p>不用“每个分类各自遍历一遍”，是因为分类数会随文件类型增长，
     * 在 10 万条目下 13 个分类就是 130 万次判定，会明显拖慢搜索响应。
     * 为保持与筛选口径一致，这里复用了 {@link SidebarCategory#isWithinDays} 等同一批判定函数，
     * 而不是另写一套“差不多”的逻辑。
     */
    private void refreshSidebarCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<FileKind, Long> byKind = new EnumMap<>(FileKind.class);
        Map<String, Long> byTag = new LinkedHashMap<>();
        long total = 0;
        long recent = 0;
        long favorites = 0;

        for (FileItem item : searchFiltered) {
            total++;
            if (SidebarCategory.isWithinDays(item, SidebarCategory.RECENT_DAYS)) {
                recent++;
            }
            byKind.merge(item.kind(), 1L, Long::sum);
            if (favoriteStore.isFavorite(item.path())) {
                favorites++;
            }
            // 标签是"每个条目可能属于多个分类"，所以不能用 bucket 求和，
            // 而是顺着条目自身的标签逐个累加——这样也不会漏掉只被一个文件使用的标签
            for (String tag : tagStore.tagsOf(item.path())) {
                byTag.merge(tag, 1L, Long::sum);
            }
        }

        counts.put(SidebarCategory.ALL, total);
        counts.put(SidebarCategory.RECENT, recent);
        counts.put(SidebarCategory.FAVORITES, favorites);
        byKind.forEach((kind, value) -> counts.put(SidebarCategory.kindId(kind), value));
        byTag.forEach((tag, value) -> counts.put(SidebarCategory.tagId(tag), value));
        sidebar.setCounts(counts);
    }

    /** 分类列表只看“有没有这种类型的文件”，不随搜索变化，避免侧栏在输入时来回跳。 */
    private void rebuildCategories() {
        EnumSet<FileKind> present = EnumSet.noneOf(FileKind.class);
        for (FileItem item : allItems) {
            present.add(item.kind());
        }

        List<SidebarCategory> categories = new ArrayList<>();
        categories.add(SidebarCategory.all());
        categories.add(SidebarCategory.modifiedWithin(SidebarCategory.RECENT_DAYS));
        // 收藏与标签在"确实有内容"时才出现，避免侧栏里挂着一堆恒为 0 的入口
        if (!favoriteStore.paths().isEmpty()) {
            categories.add(SidebarCategory.favorites(
                    item -> favoriteStore.isFavorite(item.path())));
        }
        tagStore.tagCounts().keySet().forEach(tag ->
                categories.add(SidebarCategory.ofTag(tag,
                        item -> tagStore.hasTag(item.path(), tag))));
        for (FileKind kind : FileKind.values()) {
            // DIRECTORY 由“显示文件夹”开关控制，单独列一个分类没有意义
            if (kind != FileKind.DIRECTORY && present.contains(kind)) {
                categories.add(SidebarCategory.ofKind(kind));
            }
        }
        sidebar.setCategories(categories);
        // 记录 id 顺序，供自检断言用；放在这里而不是另写一份构建逻辑，
        // 避免“自检看到的分类”与“界面上的分类”慢慢不一致
        categoryIds = categories.stream().map(SidebarCategory::id).toList();
    }

    // ------------------------------------------------------------------ 扫描

    public Parent getRootPane() {
        return rootPane;
    }

    /** 首次进入时的扫描。 */
    public void startInitialScan() {
        scan(root);
    }

    /** 切换到新根目录并重新扫描。 */
    public void scan(Path newRoot) {
        if (newRoot == null) {
            return;
        }
        boolean rootChanged = this.root == null || !this.root.equals(newRoot.toAbsolutePath().normalize());
        this.root = newRoot.toAbsolutePath().normalize();
        this.rootReason = "用户选择";
        this.hiddenStats = null;
        this.lastResult = null;
        this.cancelled.set(false);
        allItems.clear();
        if (rootChanged) {
            // 配置、删除日志、最近使用、收藏、标签、索引缓存都是"随文件夹走"的（设计决策 D1），
            // 换根目录就要整套换掉，否则会把 A 文件夹的数据写到 B 里
            persistUiState();
            prepareStores(this.root);
            UiState state = configStore.load();
            showFolders.setSelected(state.showFolders);
            // 走与启动时同一份迁移逻辑（此前这里直接 applyColumns，绕过了 layoutVersion 判断）
            applyColumnsWithMigration(state);
            if (state.sortColumnId != null) {
                table.applySort(state.sortColumnId, state.sortAscending);
            } else {
                table.applyDefaultSort();
            }
            recentStrip.setCollapsed(state.recentStripCollapsed, false);
            applyTheme(state.theme);
            refreshRecentStrip();
        }
        updateRootLabel();
        updateStatus();

        // 先用缓存把列表画出来，再在后台重新扫描并替换。
        // 这是"二次启动秒开"的关键：大目录下用户不必盯着加载页等。
        boolean fromCache = showCachedResultIfAvailable();
        if (!fromCache) {
            showOnlyLoading();
        }

        final long myGeneration = generation.incrementAndGet();
        final Path scanRoot = this.root;

        workers.submit(() -> {
            Scanner scanner = new Scanner(exclusions,
                    progress -> Platform.runLater(() -> onProgress(progress, myGeneration)));
            ScanResult result = scanner.scan(scanRoot, cancelled::get);
            Platform.runLater(() -> onScanFinished(result, myGeneration));
        });
    }

    /**
     * 尝试用索引缓存先把界面填起来。
     *
     * @return 是否成功用上了缓存
     */
    private boolean showCachedResultIfAvailable() {
        long startedAt = System.nanoTime();
        Optional<ScanResult> cached = indexCache.load(root);
        if (cached.isEmpty()) {
            showingCachedResult = false;
            cacheUsedAtStartup = false;
            return false;
        }
        ScanResult result = cached.get();
        this.lastResult = result;
        allItems.setAll(result.items());
        rebuildCategories();
        refilter();
        centerStack.getChildren().setAll(table);
        detailsButton.setDisable(!hasDetails());
        showingCachedResult = true;
        cacheUsedAtStartup = true;
        cacheLoadMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        updateStatus();
        return true;
    }

    private void onProgress(ScanProgress progress, long scanGeneration) {
        if (generation.get() != scanGeneration) {
            return;
        }
        progressEvents.incrementAndGet();
        loadingView.update(progress, root);
    }

    private void onScanFinished(ScanResult result, long scanGeneration) {
        if (generation.get() != scanGeneration) {
            // 已被更新的扫描取代，丢弃过期结果
            return;
        }
        this.lastResult = result;
        allItems.setAll(result.items());
        rebuildCategories();
        refilter();
        showingCachedResult = false;
        scanCompletions.incrementAndGet();

        centerStack.getChildren().setAll(table);
        detailsButton.setDisable(!hasDetails());

        // 落盘索引缓存，供下次启动秒开。失败（例如条目过多）不影响任何流程。
        indexCache.save(result);

        // 顺手清理"指向已不存在文件"的用户数据。
        //
        // 为什么放在扫描完成的时刻：这时才知道磁盘上现在有什么。三类数据里
        // 最近使用与内容索引本来就各自有清理入口，只有标签一直没人管——
        // TagStore.pruneMissing 写了实现却在生产代码里零调用者，
        // 于是"文件被外部删掉（在资源管理器里删、被别的程序删）"之后，
        // 侧栏会永远留着一个点进去是空的标签分类。
        //
        // 判定用"路径在磁盘上是否还存在"，而不是"是否出现在本次扫描结果里"：
        // 后者会把被排除规则藏起来的文件（例如 .venv 里的东西）误判成已删除，
        // 那样用户给一个被隐藏的文件打的标签会被无端清掉。
        // 放在后台线程：它要逐个路径做文件系统查询，不该占着界面线程。
        workers.submit(() -> {
            int pruned = tagStore.pruneMissing(java.nio.file.Files::exists);
            if (pruned > 0) {
                Platform.runLater(() -> {
                    rebuildCategories();
                    updateStatus();
                });
            }
        });

        // 被剪枝目录的内部规模延后统计：不让它拖慢首屏
        if (!result.excludedDirs().isEmpty()) {
            workers.submit(() -> {
                HiddenStats measured = Scanner.measureHidden(
                        result.excludedDirs(), cancelled::get, Scanner.MAX_HIDDEN_MEASURE_FILES);
                HiddenStats total = Scanner.totalHidden(result, measured);
                Platform.runLater(() -> {
                    if (generation.get() != scanGeneration) {
                        return;
                    }
                    hiddenStats = total;
                    detailsButton.setDisable(!hasDetails());
                    updateStatus();
                });
            });
        }
    }

    private void showOnlyLoading() {
        loadingView.reset();
        centerStack.getChildren().setAll(loadingView);
    }

    // ------------------------------------------------------------------ 状态

    private void updateRootLabel() {
        String name = root == null || root.getFileName() == null
                ? String.valueOf(root) : root.getFileName().toString();
        rootLabel.setText(name);
        rootLabel.setTooltip(new Tooltip(
                (root == null ? "" : root.toString()) + (rootReason.isEmpty() ? "" : "\n（" + rootReason + "）")));
        if (stage != null) {
            stage.setTitle(MainApp.APP_NAME + " — " + name);
        }
    }

    private void updateStatus() {
        StringBuilder sb = new StringBuilder();
        if (createProgressHint != null && !createProgressHint.isBlank()) {
            sb.append(createProgressHint).append("　·　");
        }
        if (contentIndexHint != null && !contentIndexHint.isBlank()) {
            sb.append(contentIndexHint).append("　·　");
        }
        if (transientHint != null && !transientHint.isBlank()) {
            sb.append(transientHint).append("　·　");
        }
        ScanResult result = lastResult;

        if (result != null) {
            boolean filtering = !query.isEmpty()
                    || !SidebarCategory.ALL.equals(sidebar.selected().id());
            if (filtering) {
                sb.append("匹配 ").append(FormatUtil.count(sortedItems.size())).append(" 项");
                if (!query.isEmpty()) {
                    sb.append(" · 筛选 ").append(lastFilterMillis).append(" ms");
                }
                sb.append(" · ");
            }
            // 用活计数而非扫描快照：删除/重命名之后再引用扫描结果就会显示过时的数字
            sb.append("共 ").append(FormatUtil.count(liveFileCount)).append(" 个文件");
            if (liveDirCount > 0) {
                sb.append(" · ").append(FormatUtil.count(liveDirCount)).append(" 个文件夹");
            }
            sb.append(" · 占用 ").append(FormatUtil.size(liveBytes));
            sb.append(" · 扫描耗时 ").append(FormatUtil.duration(result.elapsedMillis()));

            HiddenStats hidden = hiddenStats;
            if (hidden != null) {
                long hiddenItems = hidden.fileCount() + hidden.dirCount();
                sb.append(" · 已隐藏 ").append(FormatUtil.count(hiddenItems)).append(" 项（")
                        .append(FormatUtil.size(hidden.bytes()));
                if (hidden.truncated()) {
                    sb.append("以上");
                }
                sb.append("）");
            } else if (!result.excludedDirs().isEmpty() || result.excludedFileCount() > 0) {
                sb.append(" · 正在统计已隐藏内容…");
            }

            if (result.hiddenCount() > 0) {
                sb.append(" · 隐藏属性 ").append(FormatUtil.count(result.hiddenCount())).append(" 项");
            }
            if (result.hasErrors()) {
                sb.append(" · ").append(FormatUtil.count(result.errorCount())).append(" 个位置读取失败");
            }
            if (result.truncated()) {
                sb.append(" · 结果已截断");
            }
            if (result.cancelled()) {
                sb.append(" · 已取消（显示的是部分结果）");
            }
            if (showingCachedResult) {
                sb.append(" · 这是上次扫描的结果，正在后台刷新…");
            }
        } else {
            sb.append("正在读取当前文件夹及子文件夹的文件信息…");
        }
        statusLabel.setText(sb.toString());
    }

    private boolean hasDetails() {
        ScanResult result = lastResult;
        return result != null && (result.hasErrors()
                || !result.excludedDirs().isEmpty()
                || result.excludedFileCount() > 0);
    }

    /**
     * 展示“被隐藏了什么 / 哪里读不了”。
     *
     * <p>这个入口不是锦上添花：排除规则会把大量文件藏起来，
     * 如果没有地方说明“藏了什么”，用户只会觉得文件丢了。
     */
    private void showDetails() {
        ScanResult result = lastResult;
        if (result == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();

        sb.append("排除规则：").append(exclusions.describe()).append('\n');
        sb.append("删除策略：").append(deletePolicy.describe()).append('\n');
        sb.append("收藏 ").append(favoriteStore.size()).append(" 项；打了标签的文件 ")
                .append(tagStore.taggedFileCount()).append(" 个；最近使用 ")
                .append(recentStore.size()).append(" 条\n");
        sb.append("数据目录：").append(root.resolve(ConfigStore.DIR_NAME)).append('\n');
        if (result.excludedFileCount() > 0) {
            sb.append("按文件名规则直接排除的文件：")
                    .append(FormatUtil.count(result.excludedFileCount()))
                    .append(" 个（").append(FormatUtil.size(result.excludedFileBytes())).append("）\n");
        }
        HiddenStats hidden = hiddenStats;
        if (hidden != null) {
            sb.append("隐藏内容合计：").append(FormatUtil.count(hidden.fileCount() + hidden.dirCount()))
                    .append(" 项，").append(FormatUtil.size(hidden.bytes()))
                    .append(hidden.truncated() ? "（已达统计上限，实际更多）" : "").append('\n');
        }

        if (!result.excludedDirs().isEmpty()) {
            sb.append("\n被整目录跳过的位置（共 ").append(result.excludedDirs().size()).append(" 个）：\n");
            result.excludedDirs().stream()
                    .limit(40)
                    .forEach(d -> sb.append("  · ").append(relativize(d)).append('\n'));
            if (result.excludedDirs().size() > 40) {
                sb.append("  … 其余 ").append(result.excludedDirs().size() - 40).append(" 个略\n");
            }
        }

        if (result.hasErrors()) {
            sb.append("\n读取失败的位置（共 ").append(result.errorCount()).append(" 个）：\n");
            for (ScanError error : result.errors()) {
                sb.append("  · ").append(error.summary()).append('\n');
            }
            if (result.errorCount() > result.errors().size()) {
                sb.append("  … 其余 ").append(result.errorCount() - result.errors().size()).append(" 个略\n");
            }
        }

        TextArea area = new TextArea(sb.toString());
        area.setEditable(false);
        area.setWrapText(false);
        area.setPrefRowCount(22);
        area.setPrefColumnCount(90);
        area.getStyleClass().add("details-area");

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("扫描详情");
        dialog.setHeaderText("已隐藏的内容与读取失败的位置");
        dialog.initOwner(stage);
        dialog.getDialogPane().setContent(area);
        dialog.getDialogPane().getButtonTypes().add(javafx.scene.control.ButtonType.CLOSE);
        dialog.getDialogPane().setPrefWidth(860);
        dialog.showAndWait();
    }

    private String relativize(Path path) {
        try {
            return root.relativize(path).toString();
        } catch (RuntimeException e) {
            return path.toString();
        }
    }

    /** 窗口关闭时调用：先落盘界面状态，再停止后台任务。 */
    public void shutdown() {
        persistUiState();
        cancelled.set(true);
        if (contentIndexer != null) {
            contentIndexer.shutdown();
        }
        workers.shutdownNow();
    }

    // ------------------------------------------------------------- 自检接口

    /** 供自检与测试使用：当前根目录。 */
    public Path currentRoot() {
        return root;
    }

    /** 供自检与测试使用：上一次扫描结果。 */
    public ScanResult lastResult() {
        return lastResult;
    }

    /** 供测试使用：当前表格展示的条目。 */
    public List<FileItem> visibleItems() {
        return List.copyOf(sortedItems);
    }

    /** 供自检使用：状态栏当前文本。 */
    public String statusText() {
        return statusLabel.getText();
    }

    /** 供自检使用：累计收到的进度回调次数。 */
    public long progressEventCount() {
        return progressEvents.get();
    }

    /** 供自检使用：切换“显示文件夹”（等价于用户点那个复选框）。 */
    public void setShowFolders(boolean value) {
        showFolders.setSelected(value);
    }

    /** 供自检使用：设置搜索词并立即生效（跳过防抖）。 */
    public void searchNow(String text) {
        searchBar.setText(text);
        searchBar.dispatchNow();
    }

    /** 供自检使用：搜索框下方的解析结果提示。 */
    public String searchHint() {
        return searchBar.hintText();
    }

    /** 供自检使用：最近一次筛选耗时（毫秒）。 */
    public long lastFilterMillis() {
        return lastFilterMillis;
    }

    /** 供自检使用：当前搜索命中数（分类筛选之前）。 */
    public int searchMatchCount() {
        return searchFiltered.size();
    }

    /** 供自检使用：侧栏选中某个分类。 */
    public void selectCategory(String id) {
        sidebar.select(id);
    }

    /** 供自检使用：侧栏计数快照。 */
    public Map<String, Long> sidebarCounts() {
        return sidebar.countsSnapshot();
    }

    /** 供自检使用：侧栏当前分类 id 列表。 */
    public List<String> sidebarCategoryIds() {
        return categoryIds;
    }

    /** 供自检使用：当前排序信息。 */
    public String sortInfo() {
        return table.sortColumnId() + " " + (table.sortAscending() ? "升序" : "降序");
    }

    /** 供自检使用：最近七天截止日期，便于断言“最近”分类的语义。 */
    public LocalDate recentCutoff() {
        return LocalDate.now().minusDays(SidebarCategory.RECENT_DAYS);
    }

    /** 供自检使用：选中第 N 行。 */
    public void selectRow(int index) {
        if (index >= 0 && index < sortedItems.size()) {
            table.getSelectionModel().clearAndSelect(index);
        }
    }

    /** 供自检使用：右键菜单的菜单项文本（不含分隔线）。 */
    public List<String> contextMenuLabels() {
        ContextMenu menu = table.getContextMenu();
        if (menu == null) {
            return List.of();
        }
        return menu.getItems().stream()
                .filter(item -> !(item instanceof SeparatorMenuItem))
                .map(MenuItem::getText)
                .toList();
    }

    /** 供自检使用：当前删除策略的描述。 */
    public String deletePolicyDescription() {
        return deletePolicy.describe();
    }

    /** 供自检使用：对当前选中项的删除方案描述（只计算，不执行）。 */
    public String deletePlanForSelection() {
        List<FileItem> selected = selectedItems();
        if (selected.isEmpty()) {
            return "";
        }
        return DeleteService.describePlan(deletePolicy.plan(selected), deletePolicy);
    }

    /** 供自检使用：当前环境是否支持打开文件。 */
    public boolean openSupported() {
        return OpenService.isOpenSupported();
    }

    // --------------------------------------------------- M4：自检接口

    /** 供自检使用：索引缓存的状态（是否存在、能否读回、条目数）。 */
    public String indexCacheStatus() {
        boolean exists = indexCache.exists();
        Optional<ScanResult> loaded = indexCache.load(root);
        return "存在=" + exists
                + " 可读=" + loaded.isPresent()
                + " 缓存文件数=" + loaded.map(r -> String.valueOf(r.fileCount())).orElse("-");
    }

    /** 供自检使用：最近使用记录数。 */
    public int recentCount() {
        return recentStore.size();
    }

    /** 供自检使用：最近使用的第一条路径。 */
    public String topRecentPath() {
        List<RecentStore.Entry> list = recentStore.recent(1);
        return list.isEmpty() ? "" : list.get(0).path;
    }

    /** 供自检使用：收藏数量。 */
    public int favoriteCount() {
        return favoriteStore.size();
    }

    /** 供自检使用：收藏与标签的往返一致性（写进去再读回来必须一致）。 */
    public String storeRoundTripStatus() {
        List<FileItem> items = visibleItems();
        if (items.isEmpty()) {
            return "无可测试条目";
        }
        FileItem probe = items.get(0);
        boolean wasFavorite = favoriteStore.isFavorite(probe.path());
        List<String> before = tagStore.tagsOf(probe.path());

        StringBuilder sb = new StringBuilder();
        sb.append("探测条目=").append(probe.name());
        sb.append(" 收藏=").append(wasFavorite);
        sb.append(" 标签=").append(before);

        // 往返：写 -> 读 -> 还原，确保不留下任何痕迹
        favoriteStore.setFavorite(probe.path(), !wasFavorite);
        boolean toggled = favoriteStore.isFavorite(probe.path());
        favoriteStore.setFavorite(probe.path(), wasFavorite);

        tagStore.setTags(probe.path(), List.of("文件面板自检标记"));
        List<String> written = tagStore.tagsOf(probe.path());
        tagStore.setTags(probe.path(), before);
        List<String> restored = tagStore.tagsOf(probe.path());

        sb.append(" 翻转后=").append(toggled);
        sb.append(" 写入标签后=").append(written);
        sb.append(" 还原后=").append(restored);

        boolean ok = toggled == !wasFavorite
                && written.equals(List.of("文件面板自检标记"))
                && restored.equals(before)
                && favoriteStore.isFavorite(probe.path()) == wasFavorite;
        sb.append(" 结论=").append(ok ? "一致" : "不一致");
        return sb.toString();
    }

    /** 供自检使用：最近使用卡片区是否收起。 */
    public boolean recentStripCollapsed() {
        return recentStrip.isCollapsed();
    }

    /** 供自检使用：切换最近使用卡片区的收起状态。 */
    public void setRecentStripCollapsed(boolean collapsed) {
        recentStrip.setCollapsed(collapsed, true);
    }

    // --------------------------------------------------- M5：自检接口

    /** 供自检使用：工具栏上是否存在某个按钮（按文本匹配）。 */
    public boolean hasToolbarButton(String label) {
        if (rootPane.getTop() instanceof VBox top && !top.getChildren().isEmpty()
                && top.getChildren().get(0) instanceof HBox header) {
            return header.getChildren().stream()
                    .filter(node -> node instanceof javafx.scene.control.ButtonBase)
                    .anyMatch(node -> label.equals(
                            ((javafx.scene.control.ButtonBase) node).getText()));
        }
        return false;
    }

    /** 供自检使用：当前是否可以撤销批量重命名。 */
    public boolean canUndoRename() {
        return batchRenameService != null && batchRenameService.hasUndoable();
    }

    /** 供自检使用：撤销按钮是否可用（应与 {@link #canUndoRename()} 一致）。 */
    public boolean undoButtonEnabled() {
        return !undoRenameButton.isDisable();
    }

    /** 供自检使用：撤销入口的提示文本。 */
    public String undoDescription() {
        return batchRenameService == null ? "" : batchRenameService.describeUndoable();
    }

    /** 供自检使用：侧栏分类 id 及其计数。 */
    public Map<String, Long> sidebarCountsSnapshot() {
        return sidebarCounts();
    }

    /**
     * 供自检使用：走"点击星标"的同一条代码路径切换一次收藏，然后还原。
     *
     * <p>刻意不复用 store 的读写来做验证——那样只能证明存储层没问题，
     * 证明不了"表格里的星标点击有没有接上存储层"。
     */
    public String favoriteToggleRoundTrip() {
        List<FileItem> items = visibleItems();
        if (items.isEmpty()) {
            return "无可测试条目";
        }
        FileItem probe = items.get(0);
        boolean before = favoriteStore.isFavorite(probe.path());
        badges.toggleFavorite(probe);
        boolean afterToggle = favoriteStore.isFavorite(probe.path());
        badges.toggleFavorite(probe);
        boolean restored = favoriteStore.isFavorite(probe.path());

        boolean ok = afterToggle != before && restored == before;
        return "条目=" + probe.name()
                + " 切换前=" + before
                + " 切换后=" + afterToggle
                + " 还原后=" + restored
                + " 结论=" + (ok ? "一致" : "不一致");
    }

    /** 供自检使用：最近使用记录里是否包含某个路径（用于验证记录写入链路）。 */
    public boolean recentContainsSelected() {
        List<FileItem> items = visibleItems();
        return !items.isEmpty() && recentStore.contains(items.get(0).path());
    }

    /** 供自检使用：本次启动是否用上了索引缓存（秒开是否生效）。 */
    public boolean cacheUsedAtStartup() {
        return cacheUsedAtStartup;
    }

    /** 供自检使用：用缓存把列表画出来所花的毫秒数；未用缓存时为 -1。 */
    public long cacheLoadMillis() {
        return cacheLoadMillis;
    }

    /** 供自检使用：后台扫描已完成的次数。这是判断"首次扫描结束"的唯一可靠信号。 */
    public long scanCompletionCount() {
        return scanCompletions.get();
    }

    // ------------------------------------------------- 图标与缩略图（M8）

    /**
     * 供自检使用：孤儿标签清理是否真的生效。
     *
     * <p>背景：{@code TagStore.pruneMissing} 写了实现却长期<b>零调用者</b>，
     * 于是文件在资源管理器里被删掉之后，侧栏会永远留着一个点进去是空的标签分类。
     * 这条断言直接造一个指向不存在路径的标签，走一遍与生产代码相同的清理入口，
     * 确认它被清掉，然后还原现场。
     */
    public String staleReferenceCheck(List<String> failures) {
        StringBuilder sb = new StringBuilder("  --- 孤儿数据清理 ---\n");
        Path ghost = root.resolve("__filepanel_自检_不存在的文件__.txt");
        if (Files.exists(ghost)) {
            sb.append("    跳过：自检用的占位路径居然真实存在\n");
            return sb.toString();
        }
        tagStore.setTags(ghost, List.of("自检孤儿标签"));
        boolean added = !tagStore.tagsOf(ghost).isEmpty();
        int pruned = tagStore.pruneMissing(Files::exists);
        boolean gone = tagStore.tagsOf(ghost).isEmpty();
        sb.append("    造一个指向不存在路径的标签：写入成功=").append(added)
                .append("　清理条数=").append(pruned)
                .append("　已清除=").append(gone).append('\n');
        if (!added) {
            failures.add("无法写入用于自检的孤儿标签，这一项结论不可信");
        }
        if (!gone) {
            failures.add("指向不存在文件的标签没有被清理（TagStore.pruneMissing 没接上）");
        }
        // 再确认"存在的文件"不会被误清：自检当前根目录一定存在
        tagStore.setTags(root, List.of("自检保留标签"));
        int secondPass = tagStore.pruneMissing(Files::exists);
        boolean kept = !tagStore.tagsOf(root).isEmpty();
        tagStore.setTags(root, List.of());
        sb.append("    对存在的路径再清一次：误清条数=").append(secondPass)
                .append("　仍然保留=").append(kept).append("（应为 true）\n");
        if (!kept || secondPass != 0) {
            failures.add("清理逻辑误删了指向仍存在路径的标签");
        }
        return sb.toString();
    }


    /**
     * 供自检使用：图标链路的断言。
     *
     * <p>这里刻意把"能取到图标"与"取不到时能降级"<b>都</b>断言一遍。
     * 只断言前者的话，一旦在别的机器上 Shell 取不到图标（被安全软件拦、旧系统），
     * 我们不会知道界面是"降级成色块"还是"变成了空白"——而后者才是灾难。
     */
    public String iconRoundTrip(List<String> failures) {
        StringBuilder sb = new StringBuilder("  --- 图标与缩略图（M8）---\n");
        sb.append("    图标来源已接入表格：").append(table.icons() == icons).append('\n');
        if (table.icons() != icons) {
            failures.add("表格没有拿到图标来源，名称列会一直是色块");
        }

        boolean shell = icons.systemIconsAvailable();
        sb.append("    系统图标可用：").append(shell)
                .append("　ICONINFO 声明大小=").append(ShellIcons.iconInfoSize())
                .append("（64 位应为 32）").append('\n');
        if (ShellIcons.iconInfoSize() != 32) {
            failures.add("ICONINFO 结构体大小异常（" + ShellIcons.iconInfoSize()
                    + "），字段顺序或对齐写错会导致读到垃圾数据");
        }

        if (shell) {
            javafx.scene.image.Image docx = icons.systemIconFor("docx");
            javafx.scene.image.Image exe = icons.systemIconFor("exe");
            sb.append("    真实图标：.docx=").append(describeImage(docx))
                    .append("　.exe=").append(describeImage(exe)).append('\n');
            if (docx == null || docx.getWidth() <= 0) {
                failures.add("取不到 .docx 的系统图标");
            }
            if (exe == null || exe.getWidth() <= 0) {
                failures.add("取不到 .exe 的系统图标");
            }
            // 不同扩展名必须拿到不同图标，否则说明"按扩展名取"这件事没生效
            if (docx != null && exe != null && samePixels(docx, exe)) {
                failures.add("docx 与 exe 的图标像素完全一致，怀疑所有扩展名拿到了同一张图");
            }
        } else {
            sb.append("    [说明] 本机取不到系统图标，将一直使用自带色块（这是允许的降级）\n");
        }

        // 缩略图：找一个真实的图片文件，走一遍同步解码链路
        FileItem image = null;
        FileItem nonImage = null;
        for (FileItem item : allItems) {
            if (item.directory()) {
                continue;
            }
            if (image == null && FileIcons.isThumbnailable(item)) {
                image = item;
            }
            if (nonImage == null && !item.ext().isEmpty() && !FileIcons.isThumbnailable(item)) {
                nonImage = item;
            }
        }
        if (image == null) {
            sb.append("    缩略图：当前文件夹里没有图片文件，跳过这一项\n");
        } else {
            javafx.scene.image.Image thumb = icons.loadThumbnailSynchronously(image.path());
            sb.append("    缩略图样本：").append(image.name()).append("（")
                    .append(FormatUtil.size(image.size())).append("）→ ")
                    .append(describeImage(thumb)).append('\n');
            if (thumb == null) {
                failures.add("图片「" + image.name() + "」解不出缩略图，却没有任何报错");
            } else if (thumb.getWidth() > FileIcons.ICON_SIZE * 3) {
                failures.add("缩略图没有按显示尺寸解码（" + thumb.getWidth()
                        + "px），大图会拖慢滚动");
            }
        }
        if (nonImage != null) {
            boolean thumbnailable = FileIcons.isThumbnailable(nonImage);
            sb.append("    非图片不应做缩略图：").append(nonImage.name())
                    .append(" → ").append(thumbnailable ? "会做（错）" : "不会做（对）").append('\n');
            if (thumbnailable) {
                failures.add("非图片文件「" + nonImage.name() + "」被判为需要缩略图");
            }
        }

        // 改过指纹的图片不能再命中旧缩略图（键里带大小+修改时间）
        if (image != null) {
            FileItem touched = new FileItem(image.name(), image.path(), image.relPath(),
                    image.parentRel(), false, image.ext(), image.kind(), image.size() + 1,
                    image.created(), image.modified(), image.accessed(), image.depth(),
                    image.hidden(), image.readOnly(), image.system());
            boolean keyChanged = !FileIcons.thumbnailKey(image).equals(FileIcons.thumbnailKey(touched));
            sb.append("    指纹变化后缓存键改变：").append(keyChanged).append('\n');
            if (!keyChanged) {
                failures.add("缩略图缓存键没有体现文件大小变化，图片被编辑后会一直显示旧缩略图");
            }
        }

        sb.append("    缩略图缓存：").append(icons.thumbnailCount()).append(" 条　淘汰 ")
                .append(icons.thumbnailEvictions()).append(" 次（上限 ")
                .append(FileIcons.MAX_THUMBNAILS).append("）")
                .append("　[自检不渲染表格，故通常为 0；真实渲染效果见 M8 截图]\n");
        return sb.toString();
    }

    private static String describeImage(javafx.scene.image.Image image) {
        if (image == null) {
            return "无";
        }
        return (int) image.getWidth() + "×" + (int) image.getHeight() + "px";
    }

    /**
     * 两张图片是否逐像素完全一致。
     *
     * <p>刻意比对<b>整张图</b>而不是某一个像素：图标四角都是透明的，
     * 拿 (0,0) 去比必然"相同"——这是这个断言第一次写错的地方。
     * 要判断"是不是同一张图"，只能整体比。
     */
    private static boolean samePixels(javafx.scene.image.Image a, javafx.scene.image.Image b) {
        try {
            javafx.scene.image.PixelReader pa = a.getPixelReader();
            javafx.scene.image.PixelReader pb = b.getPixelReader();
            if (pa == null || pb == null) {
                return false;
            }
            int w = (int) Math.min(a.getWidth(), b.getWidth());
            int h = (int) Math.min(a.getHeight(), b.getHeight());
            if (w <= 0 || h <= 0) {
                return false;
            }
            int differing = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (pa.getArgb(x, y) != pb.getArgb(x, y)) {
                        differing++;
                    }
                }
            }
            return differing == 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // --------------------------------------------- 搜索范围与语法面板（M10）
    /** 供自检使用：切换搜索范围。 */
    public void setSearchScopeForSelfTest(SearchScope scope) {
        searchBar.setScope(scope);
        searchBar.dispatchNow();
    }

    public SearchScope searchScope() {
        return searchBar.scope();
    }

    /** 供自检使用：展开语法面板。 */
    public void setSyntaxPanelVisible(boolean visible) {
        searchBar.setSyntaxPanelVisible(visible);
    }

    public boolean syntaxPanelVisible() {
        return searchBar.syntaxPanelVisible();
    }

    public int syntaxExampleCount() {
        return searchBar.syntaxExampleCount();
    }

    /** 供自检使用：当前搜索提示（含范围与降级说明）。 */
    public String currentSearchHint() {
        return searchBar.hintText();
    }

    /** 供自检使用：当前查询里各条件的自然语言描述。 */
    public String currentQueryDescription() {
        return query.describe();
    }

    /**
     * 供自检使用：搜索范围与语法面板的断言。
     *
     * <p>范围这一项最容易出的错是"选了但没生效"或"生效了但提示没说"——两者用户都看不出来，
     * 只会觉得搜索结果莫名其妙。所以这里两件事都断言：<b>命中集合真的变了</b>，
     * 以及<b>提示里真的说了</b>。
     */
    public String searchScopeRoundTrip(List<String> failures) {
        StringBuilder sb = new StringBuilder("  --- 搜索范围与语法面板（M10）---\n");

        // 1) 语法面板：内容与可点击性
        setSyntaxPanelVisible(true);
        java.util.List<String> syntaxLabels = SearchParser.syntaxItems().stream()
                .map(SearchParser.SyntaxItem::label).toList();
        sb.append("    语法面板：可见=").append(syntaxPanelVisible())
                .append("　可点击例子=").append(syntaxExampleCount())
                .append("　条目=").append(String.join(" / ", syntaxLabels)).append('\n');
        if (!syntaxPanelVisible()) {
            failures.add("语法面板打不开");
        }
        if (syntaxExampleCount() < 10) {
            failures.add("语法面板里可点击的例子太少（" + syntaxExampleCount()
                    + "），普通用户没有可点的东西就等于没做");
        }

        // 2) 范围 = 文件名：应当只匹配文件名，不再匹配所在路径
        searchNow("");
        SearchScope original = searchScope();
        setSearchScopeForSelfTest(SearchScope.ALL);
        // 找一个"名字里没有、但所在路径里有"的样本：用目录名做关键词
        String folderToken = null;
        FileItem sampleInside = null;
        for (FileItem item : allItems) {
            if (item.directory() || item.parentRel() == null || item.parentRel().isEmpty()) {
                continue;
            }
            String firstSegment = item.parentRel().split("/")[0];
            if (firstSegment.length() >= 2 && !item.name().contains(firstSegment)) {
                folderToken = firstSegment;
                sampleInside = item;
                break;
            }
        }
        if (folderToken == null) {
            sb.append("    范围对比：当前目录里找不到「名字不含、路径含」的样本，跳过这一项\n");
        } else {
            searchNow(folderToken);
            int byAll = searchMatchCount();
            searchNow("");
            setSearchScopeForSelfTest(SearchScope.NAME);
            searchNow(folderToken);
            int byName = searchMatchCount();
            String hint = currentSearchHint();
            sb.append("    关键词「").append(folderToken).append("」（样本路径 ").append(sampleInside.relPath())
                    .append("）：范围=全部 命中 ").append(byAll)
                    .append(" 项；范围=文件名 命中 ").append(byName).append(" 项\n");
            sb.append("    提示：").append(hint).append('\n');
            if (byAll <= byName) {
                failures.add("把范围切到「文件名」后命中数没有下降（全部 " + byAll + " → 文件名 " + byName
                        + "），说明范围没有真正生效");
            }
            if (!hint.contains("范围：文件名")) {
                failures.add("提示里没有说明当前范围：「" + hint + "」");
            }
            if (!currentQueryDescription().contains("文件名包含")) {
                failures.add("范围=文件名时查询条件应当是「文件名包含」，实际：" + currentQueryDescription());
            }
        }
        searchNow("");
        setSearchScopeForSelfTest(SearchScope.ALL);

        // 3) 范围 = 类型：认识中文类型名，不认识的要降级并说明
        setSearchScopeForSelfTest(SearchScope.KIND);
        searchNow("图片");
        sb.append("    范围=类型，关键词「图片」：").append(currentQueryDescription()).append('\n');
        if (!currentQueryDescription().contains("类型")) {
            failures.add("范围=类型时「图片」应当解析成类型条件，实际：" + currentQueryDescription());
        }
        searchNow("绝不是一个类型名");
        String badHint = currentSearchHint();
        sb.append("    范围=类型，关键词「绝不是一个类型名」：提示=").append(badHint).append('\n');
        if (!badHint.contains("已按普通文字搜索")) {
            failures.add("范围=类型下遇到不认识的词必须明确说明已降级，实际提示：" + badHint);
        }
        if (!currentQueryDescription().contains("包含")) {
            failures.add("降级后应当退回普通文字匹配，实际：" + currentQueryDescription());
        }

        // 4) 范围 = 大小：带默认比较方式，且显式语法优先于范围
        setSearchScopeForSelfTest(SearchScope.SIZE);
        searchNow("1MB");
        String sizeDesc = currentQueryDescription();
        sb.append("    范围=大小（默认大于），关键词「1MB」：").append(sizeDesc).append('\n');
        if (!sizeDesc.contains("大小 >")) {
            failures.add("范围=大小时应按「大于」解释裸关键词，实际：" + sizeDesc);
        }
        searchNow("size:<=100KB");
        String explicitDesc = currentQueryDescription();
        sb.append("    范围=大小，但显式写 size:<=100KB：").append(explicitDesc).append('\n');
        if (!explicitDesc.contains("大小 ≤ 100.00 KB")) {
            failures.add("显式语法必须优先于范围，实际：" + explicitDesc);
        }
        searchNow("");

        // 5) 还原
        setSearchScopeForSelfTest(original);
        sb.append("    已还原范围：").append(searchScope().label()).append('\n');
        return sb.toString();
    }

    /**
     * 供自检使用：当前生效的删除策略。
     *
     * <p>暴露它而不是暴露"阈值"这种单个数字，是为了让自检能直接用策略对象去断言
     * 判定结果，而不是在测试里重算一遍规则——重算一遍等于把实现抄进测试，
     * 实现错了测试也会跟着错。
     */
    public DeletePolicy currentDeletePolicy() {
        return deletePolicy;
    }

    // ------------------------------------------------- 删除阈值设置自检（D4）

    /**
     * 造一个指定大小的样本条目。
     *
     * <p>用合成条目而不是真实文件：自检要断言的是"大小 → 删除方式"这条<b>纯规则</b>，
     * 而造一个真实的大文件既慢又占磁盘。这条规则本身与文件内容无关，
     * 所以合成条目不会让断言变得不真实——真正需要真实文件的是"删除动作"本身，
     * 那个由 {@code --delete-test} 在临时文件上验证。
     */
    public static FileItem sampleItemForPolicy(long sizeBytes) {
        Path path = Path.of("C:/filepanel-selftest/阈值样本.bin");
        return new FileItem("阈值样本.bin", path, "阈值样本.bin", "", false, "bin",
                FileKind.ofExtension("bin"), sizeBytes, null, null, null, 0, false, false, false);
    }

    /**
     * 供自检使用：阈值设置的完整往返验证。
     *
     * <p>重点不是"能存能读"，而是<b>三段接线都要对</b>：
     * <ol>
     *   <li>阈值 → 判定：调小阈值后，同一个条目必须由"回收站"变成"永久删除"</li>
     *   <li>阈值 → 执行器：{@code deleteService} 持有的策略必须跟着变。
     *       只改界面那一份的话，会出现"确认框说进回收站、实际却永久删除"——
     *       这是整个产品里后果最严重的一类偏差</li>
     *   <li>阈值 → 磁盘：保存后能从 {@code config.json} 读回同样的值</li>
     * </ol>
     * 全程<b>不删除任何文件</b>：自检跑在用户的真实文件夹上，只做纯计算与读写配置。
     */
    public String deleteThresholdRoundTrip(List<String> failures) {
        StringBuilder sb = new StringBuilder("  --- 删除阈值设置（D4：可配置）---\n");
        long original = deletePolicy.thresholdBytes();
        boolean originalAlways = deletePolicy.alwaysRecycle();

        sb.append("    工具栏「设置」入口：").append(hasToolbarButton("设置")).append('\n');
        sb.append("    当前策略：").append(deletePolicy.describe()).append('\n');
        if (!hasToolbarButton("设置")) {
            failures.add("工具栏上没有「设置」按钮，阈值无法配置");
        }

        // 1) 阈值 → 判定
        long sample = 2L * 1024 * 1024; // 2 MiB
        FileItem item = sampleItemForPolicy(sample);
        String atDefault = String.valueOf(
                new DeletePolicy(DeletePolicy.DEFAULT_THRESHOLD_BYTES, false).decide(item));
        String atMinimum = String.valueOf(
                new DeletePolicy(DeletePolicy.MIN_THRESHOLD_BYTES, false).decide(item));
        String atExact = String.valueOf(new DeletePolicy(sample, false).decide(item));
        String justBelow = String.valueOf(new DeletePolicy(sample + 1, false).decide(item));
        String forced = String.valueOf(
                new DeletePolicy(DeletePolicy.MIN_THRESHOLD_BYTES, true).decide(item));
        long clamped = new DeletePolicy(0, false).thresholdBytes();

        sb.append("    样本 ").append(FormatUtil.size(sample)).append(" 的条目：")
                .append("默认阈值(").append(DeletePolicy.humanSize(DeletePolicy.DEFAULT_THRESHOLD_BYTES))
                .append(")=").append(atDefault)
                .append("　阈值调到下限(").append(DeletePolicy.humanSize(DeletePolicy.MIN_THRESHOLD_BYTES))
                .append(")=").append(atMinimum).append('\n');
        sb.append("    边界：等于阈值=").append(atExact)
                .append("　比阈值小 1 字节=").append(justBelow)
                .append("　勾选“一律走回收站”=").append(forced).append('\n');
        sb.append("    下限保护：阈值输入 0 → 实际生效 ")
                .append(DeletePolicy.humanSize(clamped)).append('\n');

        if (!"RECYCLE".equals(atDefault)) {
            failures.add("默认 1 GB 阈值下，2 MB 的条目应当进回收站，实际 " + atDefault);
        }
        if (!"PERMANENT".equals(atMinimum)) {
            failures.add("阈值调到 1 MB 后，2 MB 的条目应当改为永久删除，实际 " + atMinimum
                    + "（这一条不成立，「把阈值调小来验证永久删除分支」就无从谈起）");
        }
        if (!"PERMANENT".equals(atExact)) {
            failures.add("大小恰好等于阈值时必须判为永久删除（>= 而不是 >），实际 " + atExact);
        }
        if (!"RECYCLE".equals(justBelow)) {
            failures.add("比阈值小 1 字节应当进回收站，实际 " + justBelow);
        }
        if (!"RECYCLE".equals(forced)) {
            failures.add("勾选“一律走回收站”后必须忽略阈值，实际 " + forced);
        }
        if (clamped != DeletePolicy.MIN_THRESHOLD_BYTES) {
            failures.add("阈值下限保护失效：输入 0 得到 " + clamped);
        }

        // 1b) 混合选择：D4 最关键的安全护栏。调小阈值后，"一个小文件 + 一个大文件"
        // 必须被判为混合，也就是必须弹三选一确认框，而不是静默按策略把大文件永久删掉。
        DeletePolicy low = new DeletePolicy(DeletePolicy.MIN_THRESHOLD_BYTES, false);
        List<FileItem> mixedSelection =
                List.of(sampleItemForPolicy(1024), sampleItemForPolicy(sample));
        DeletePlan mixedPlan = low.plan(mixedSelection);
        String mixedText = DeleteService.describePlan(mixedPlan, low);
        sb.append("    混合选择（1 KB + ").append(FormatUtil.size(sample)).append("）：判为混合=")
                .append(mixedPlan.isMixed()).append("　方案=").append(mixedText).append('\n');
        if (!mixedPlan.isMixed()) {
            failures.add("“小 + 大”混合选择必须判为混合，否则大文件会在用户毫无察觉的情况下被永久删除");
        }
        if (!mixedText.contains("永久删除")) {
            failures.add("混合选择的方案描述没有点明含永久删除项：" + mixedText);
        }

        // 2) 阈值 → 执行器（真正删东西时读的那一份）
        applyDeletePolicy(DeletePolicy.MIN_THRESHOLD_BYTES, false);
        long serviceThreshold = deleteService == null ? -1 : deleteService.policy().thresholdBytes();
        sb.append("    改设置后执行器实际使用的阈值：").append(serviceThreshold)
                .append("（应为 ").append(DeletePolicy.MIN_THRESHOLD_BYTES).append("）").append('\n');
        if (serviceThreshold != DeletePolicy.MIN_THRESHOLD_BYTES) {
            failures.add("改了阈值但删除执行器用的还是旧值（" + serviceThreshold
                    + "）——会导致确认框与实际删除方式不一致");
        }

        // 3) 阈值 → 磁盘
        persistUiState();
        UiState saved = configStore == null ? null : configStore.load();
        long onDisk = saved == null ? -1 : saved.deleteThresholdBytes;
        sb.append("    落盘往返：写盘后读回阈值=").append(onDisk)
                .append("　一致=").append(onDisk == DeletePolicy.MIN_THRESHOLD_BYTES).append('\n');
        if (onDisk != DeletePolicy.MIN_THRESHOLD_BYTES) {
            failures.add("阈值没有正确落盘：期望 " + DeletePolicy.MIN_THRESHOLD_BYTES + "，读回 " + onDisk);
        }

        // 还原：自检绝不给用户留下一个被改过的删除策略
        applyDeletePolicy(original, originalAlways);
        persistUiState();
        sb.append("    已还原为原策略：").append(deletePolicy.describe()).append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------ 内容搜索自检（M7）

    /** 供自检与截图使用：后台内容索引是否还在跑。 */
    public boolean contentIndexRunning() {
        return contentIndexer != null && contentIndexer.isRunning();
    }

    /** 自检探针的长度。够长才不至于在一份文档里到处都命中。 */
    private static final int CONTENT_PROBE_CHARS = 12;

    /**
     * 供自检使用：把"抽取正文 → 入索引 → 输入 content: → 出结果 → 出摘要"整条链路跑一遍。
     *
     * <h2>为什么不能在单元测试里代替</h2>
     * 单元测试各自只验证一环（抽取器、索引、查询），而全文搜索的真实故障恰恰长在<b>接缝</b>上：
     * 索引建好了但搜索没去查它、查了但摘要列没接上、没索引的文件被当成"不命中"还是"命中空串"。
     * 所以这里刻意走界面的真实入口（{@link #searchNow(String)}），而不是另写一条路径。
     *
     * <h2>探针从哪来</h2>
     * 取一份真实文件正文<b>正中间</b>的一小段。用中间段而不是开头，是为了避开页眉、
     * 标题、"XX系统技术说明书 V2.0"这类在别的文档里也会出现的样板文字——
     * 否则断言"只命中它一个"会莫名其妙地失败。
     *
     * @param failures 失败原因写这里（由调用方汇总后决定退出码）
     * @return 可直接打印的多行描述
     */
    public String contentSearchRoundTrip(List<String> failures) {
        if (contentIndex == null || contentIndexer == null) {
            failures.add("内容索引未初始化");
            return "";
        }

        FileItem probe = null;
        String text = null;
        for (FileItem item : allItems) {
            if (!ContentExtractor.isSupported(item)) {
                continue;
            }
            ContentExtractor.Extraction extraction = ContentExtractor.extract(item.path());
            // 太短的正文取不出有意义的中间段
            if (extraction.succeeded() && extraction.text().length() >= CONTENT_PROBE_CHARS * 4) {
                probe = item;
                text = extraction.text();
                break;
            }
        }
        StringBuilder sb = new StringBuilder("  --- 全文内容搜索（M7）---\n");
        if (probe == null) {
            failures.add("内容搜索自检：在当前目录里找不到任何可抽取正文的文件");
            return sb.append("    找不到可抽取正文的文件\n").toString();
        }

        String flat = text.replaceAll("\\s+", " ");
        String needle = flat.substring(flat.length() / 2,
                Math.min(flat.length(), flat.length() / 2 + CONTENT_PROBE_CHARS)).trim();
        // lambda 里只能捕获有效最终变量，而 sample 是在循环里选出来的，所以单独留一份
        final FileItem sample = probe;
        sb.append("    样本：").append(probe.name())
                .append("（正文 ").append(FormatUtil.count(text.length())).append(" 字符）\n");
        sb.append("    探针：").append(needle).append('\n');

        long modified = probe.modified() == null ? 0L : probe.modified().toMillis();
        boolean put = contentIndex.put(probe.path(), probe.size(), modified, text, false);
        contentIndex.save();
        if (!put) {
            failures.add("内容搜索自检：把样本正文写进索引失败（索引已满或参数被拒）");
        }

        // 关键设计不变式：内容搜索必须显式写 content:，普通关键词绝不去碰正文索引。
        // 反过来（"关键词顺便搜正文"）会让普通搜索莫名其妙地变慢并命中无关文件。
        boolean implicitNeedsContent = SearchParser.parse(needle).needsContent();
        boolean explicitNeedsContent = SearchParser.parse("content:" + needle).needsContent();
        sb.append("    是否触发正文检索：普通关键词=").append(implicitNeedsContent)
                .append("，content: 前缀=").append(explicitNeedsContent).append('\n');
        if (implicitNeedsContent) {
            failures.add("普通关键词不应触发正文检索（内容搜索必须是显式的）");
        }
        if (!explicitNeedsContent) {
            failures.add("content: 前缀未能触发正文检索");
        }

        searchNow("content:" + needle);
        // 等后台索引跑完再断言：索引对象不是线程安全的，边写边读会读到中间的 map 状态
        long deadline = System.currentTimeMillis() + 30_000;
        while (contentIndexer.isRunning() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (contentIndexer.isRunning()) {
            failures.add("内容搜索自检：等待后台建索引超过 30 秒");
        }

        List<FileItem> rows = visibleItems();
        sb.append("    输入 content:").append(needle).append(" 后命中 ")
                .append(searchMatchCount()).append(" 项，表格 ")
                .append(rows.size()).append(" 行\n");

        boolean probeHit = rows.stream().anyMatch(r -> r.path().equals(sample.path()));
        if (!probeHit) {
            failures.add("内容搜索自检：明明把「" + needle + "」写进了索引，却没有命中样本文件 "
                    + probe.name());
        }

        // 反向断言：逐行独立重算一遍，确保展示出来的每一行都真的满足条件。
        // 只看命中数是不够的——把"内容未索引"当成命中时数字照样好看。
        for (FileItem row : rows) {
            if (!query.matches(row, tagStore.tagsOf(row.path()),
                    favoriteStore.isFavorite(row.path()), contentIndex.textOf(row))) {
                failures.add("内容搜索自检：表格里的「" + row.name() + "」并不满足当前查询");
                break;
            }
        }

        String snippet = snippetFor(probe);
        sb.append("    摘要列可见：").append(table.isColumnVisible(FileTable.COL_SNIPPET))
                .append("　样本摘要：").append(snippet).append('\n');
        if (!table.isColumnVisible(FileTable.COL_SNIPPET)) {
            failures.add("内容搜索时摘要列没有自动出现");
        }
        if (!snippet.contains(needle)) {
            failures.add("摘要里没有出现探针「" + needle + "」，实际：" + snippet);
        }

        // 负向对照：一个必然不存在的词必须 0 命中。
        // 少了这一条，"谓词恒真"这种错法也能让上面的断言全绿。
        String absent = "__filepanel_absent_probe__";
        searchNow("content:" + absent);
        int absentHits = searchMatchCount();
        sb.append("    负向对照 content:").append(absent).append(" 命中 ")
                .append(absentHits).append(" 项\n");
        if (absentHits != 0) {
            failures.add("负向对照失败：不存在的词居然命中了 " + absentHits + " 项");
        }

        searchNow("");
        boolean hidden = !table.isColumnVisible(FileTable.COL_SNIPPET);
        sb.append("    清空查询后摘要列自动收回：").append(hidden).append('\n');
        if (!hidden) {
            failures.add("清空查询后摘要列应自动收回");
        }
        return sb.toString();
    }
}
