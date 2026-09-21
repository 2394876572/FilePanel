package com.zean.filepanel.ui;

import com.zean.filepanel.core.FileItem;
import com.zean.filepanel.store.ColumnState;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.ListChangeListener;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 文件表格（8 列）。
 *
 * <p>列的排序键与展示文本往往不是同一个东西（例如“大小”展示 {@code 1.2 MB} 但必须按字节数排序，
 * “类型”展示 {@code 文档 · DOCX} 但应按分类再按扩展名排序）。因此这类列采用
 * <b>值工厂返回模型对象、单元格工厂负责格式化</b>的写法，避免出现“按字符串排大小”这种错误排序。
 *
 * <p>每列都有稳定的 {@code id}：列宽、可见性与排序都要持久化，用中文标题当键一旦改文案就全失效。
 *
 * <h2>为什么不用 CONSTRAINED_RESIZE_POLICY</h2>
 * 它会把可用宽度在所有列之间“均分”，完全无视各列的 {@code prefWidth}
 * （实测结果是各列统统 152px，文件名只能显示四五个汉字）。
 * 所以这里用默认的 UNCONSTRAINED 策略让 prefWidth 生效，再由
 * {@link #stretchNameColumn()} 把剩余宽度补给名称列。
 */
public class FileTable extends TableView<FileItem> {

    public static final String COL_FAVORITE = "favorite";
    public static final String COL_NAME = "name";
    public static final String COL_TAGS = "tags";
    /** 内容命中摘要列。默认隐藏，做内容搜索时自动出现；不参与持久化（见 captureColumns）。 */
    public static final String COL_SNIPPET = "snippet";
    public static final String COL_TYPE = "type";
    public static final String COL_SIZE = "size";
    public static final String COL_LOCATION = "location";
    public static final String COL_CREATED = "created";
    public static final String COL_MODIFIED = "modified";

    /**
     * 默认列宽布局的版本。改动任何一列的默认宽度时都要 +1，
     * 否则老用户的配置文件会一直用旧宽度（见 UiState#layoutVersion）。
     */
    public static final int LAYOUT_VERSION = 2;

    /** 小于此宽度的持久化列宽一律忽略，避免配置文件损坏导致列被压成看不见。 */
    private static final double MIN_SANE_WIDTH = 40;

    /** 计算弹性列宽度时预留给纵向滚动条与边框的像素。 */
    private static final double LAYOUT_RESERVE = 20;

    /** 标签列最多平铺几个标签，超出用 +N 表示。 */
    private static final int MAX_VISIBLE_TAGS = 3;

    private final ItemBadges badges;

    /**
     * 图标来源（缩略图 / 系统图标），由 MainWindow 注入。
     *
     * <p>为 null 时表格退回"自带色块"这一级——单测里构建表格时不注入任何东西，
     * 也不该因此崩掉或者渲染不出文字。
     */
    private FileIcons icons;

    /** 由 MainWindow 注入：给定条目，返回它在当前内容查询下的命中摘要。 */
    private java.util.function.Function<FileItem, String> snippetProvider;

    private ContextMenu headerMenu;
    private TableColumn<FileItem, String> nameColumn;
    /** 防止“设置列宽 → 触发布局 → 再次设置列宽”形成回环。 */
    private boolean stretchingNameColumn;

    /** 设置图标来源；缩略图解码完成后会回调刷新表格（否则新解码出来的图不会自己出现）。 */
    public void setIcons(FileIcons icons) {
        this.icons = icons;
        if (icons != null) {
            icons.setOnReady(() -> {
                // 刷新整表：缩略图是异步解码的，解码完成时那一行可能已经被滚出视野，
                // 也可能正显示在屏幕上。整表刷新最简单，而且 JavaFX 只重画可见行
                refresh();
            });
        }
    }

    public FileIcons icons() {
        return icons;
    }

    public FileTable(ItemBadges badges) {
        this.badges = badges == null ? ItemBadges.NONE : badges;

        getStyleClass().add("file-table");
        setPlaceholder(new Label("没有可显示的文件"));
        // JavaFX 的 TableView 默认是【单选】。不显式改成多选的话，
        // "多选混删的三选一确认"与"批量重命名"这两条路根本走不到——
        // 用户按住 Ctrl 点第二行时第一行会被取消选中，而界面上没有任何提示。
        getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);

        TableColumn<FileItem, FileItem> favoriteCol = favoriteColumn();
        TableColumn<FileItem, String> nameCol = nameColumn();
        TableColumn<FileItem, FileItem> tagsCol = tagsColumn();
        TableColumn<FileItem, FileItem> snippetCol = snippetColumn();
        TableColumn<FileItem, FileItem> typeCol = typeColumn();
        TableColumn<FileItem, Long> sizeCol = sizeColumn();
        TableColumn<FileItem, String> locationCol =
                textColumn(COL_LOCATION, "所在位置", FileItem::locationText, 170);
        TableColumn<FileItem, FileTime> createdCol = timeColumn(COL_CREATED, "创建时间", FileItem::created);
        TableColumn<FileItem, FileTime> modifiedCol = timeColumn(COL_MODIFIED, "修改时间", FileItem::modified);

        getColumns().setAll(List.of(favoriteCol, nameCol, tagsCol, snippetCol, typeCol, sizeCol,
                locationCol, createdCol, modifiedCol));
        this.nameColumn = nameCol;

        widthProperty().addListener((obs, was, is) -> stretchNameColumn());
        getColumns().addListener((ListChangeListener<TableColumn<FileItem, ?>>) change -> {
            while (change.next()) {
                // 只需知道“变了”，无需消费具体内容
            }
            stretchNameColumn();
        });
        for (TableColumn<FileItem, ?> col : getColumns()) {
            col.visibleProperty().addListener((obs, was, is) -> stretchNameColumn());
        }

        setRowFactory(view -> new TableRow<>() {
            @Override
            protected void updateItem(FileItem item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().remove("folder-row");
                if (!empty && item != null && item.directory()) {
                    getStyleClass().add("folder-row");
                }
            }
        });

        installDragOut();
    }

    /**
     * 支持把选中行拖到资源管理器或其他程序里。
     *
     * <p>用 {@code ClipboardContent.putFiles} 而不是自定义数据格式：这是各程序都认的标准形式，
     * 拖到资源管理器是复制文件，拖到邮件客户端是添加附件，都不需要我们额外适配。
     */
    private void installDragOut() {
        setOnDragDetected(event -> {
            List<FileItem> selected = new ArrayList<>(getSelectionModel().getSelectedItems());
            if (selected.isEmpty()) {
                return;
            }
            javafx.scene.input.Dragboard board = startDragAndDrop(
                    javafx.scene.input.TransferMode.COPY);
            javafx.scene.input.ClipboardContent content =
                    new javafx.scene.input.ClipboardContent();
            content.putFiles(selected.stream().map(item -> item.path().toFile()).toList());
            board.setContent(content);
            // 拖拽时给一个可见的反馈，让用户知道"抓住了几项"
            board.setDragView(null);
            event.consume();
        });
    }

    // ------------------------------------------------------------------ 排序

    /**
     * 应用排序。
     *
     * <p><b>必须在本表已经 {@code setItems(可排序列表)} 之后调用。</b>
     * TableView 是通过把自己当前的 sortOrder 推给底层 {@code SortedList} 的比较器来实现排序的；
     * 若在 items 还是 null 时就设好 sortOrder，这次设置不会被推送出去，结果是
     * <b>表头箭头显示为“已按名称升序”，实际却按扫描顺序排列</b>——数据看着像对的，顺序是错的。
     *
     * <p>除此之外还必须在装配处把 {@code SortedList.comparatorProperty()} 绑定到
     * {@code TableView.comparatorProperty()}，否则运行时只会打印一条提示、完全不排序。
     */
    public void applySort(String columnId, boolean ascending) {
        TableColumn<FileItem, ?> col = findColumn(columnId);
        if (col == null) {
            col = getColumns().get(1);
        }
        col.setSortType(ascending ? TableColumn.SortType.ASCENDING : TableColumn.SortType.DESCENDING);
        getSortOrder().clear();
        // 用 Collections.singletonList 而不是 setAll(col) 或 List.of(col)：
        // ObservableList.setAll 是 varargs，元素类型又是通配的 TableColumn<FileItem,?>，
        // javac 会为它创建泛型数组并报 unchecked 警告。singletonList 不是 varargs，绕开了这个问题。
        getSortOrder().setAll(Collections.singletonList(col));
    }

    /** 设置内容摘要提供者；传 null 表示不显示摘要。 */
    public void setSnippetProvider(java.util.function.Function<FileItem, String> provider) {
        this.snippetProvider = provider;
        refresh();
    }

    /** 按 id 设置列的可见性。 */
    public void setColumnVisible(String columnId, boolean visible) {
        TableColumn<FileItem, ?> col = findColumn(columnId);
        if (col != null) {
            col.setVisible(visible);
        }
    }

    /** 按 id 查询列的可见性。 */
    public boolean isColumnVisible(String columnId) {
        TableColumn<FileItem, ?> col = findColumn(columnId);
        return col != null && col.isVisible();
    }

    public void applyDefaultSort() {
        applySort(COL_NAME, true);
    }

    /** 当前排序所在列的 id；无排序时返回 null。 */
    public String sortColumnId() {
        if (getSortOrder().isEmpty()) {
            return null;
        }
        return getSortOrder().get(0).getId();
    }

    public boolean sortAscending() {
        if (getSortOrder().isEmpty()) {
            return true;
        }
        return getSortOrder().get(0).getSortType() == TableColumn.SortType.ASCENDING;
    }

    private TableColumn<FileItem, ?> findColumn(String id) {
        if (id == null) {
            return null;
        }
        for (TableColumn<FileItem, ?> col : getColumns()) {
            if (id.equals(col.getId())) {
                return col;
            }
        }
        return null;
    }

    // ------------------------------------------------------ 列宽与可见性持久化

    /** 采集各列的宽度与可见性，供持久化。 */
    public List<ColumnState> captureColumns() {
        List<ColumnState> out = new ArrayList<>(getColumns().size());
        for (TableColumn<FileItem, ?> col : getColumns()) {
            // 摘要列是"随内容搜索临时出现"的显示辅助，不该被持久化：
            // 否则一次内容搜索会让它永远留在界面上，下次启动也还在
            if (COL_SNIPPET.equals(col.getId())) {
                continue;
            }
            // 布局尚未发生时 getWidth() 为 0，此时用 prefWidth 兜底，避免把 0 写进配置文件
            double width = col.getWidth() > 0 ? col.getWidth() : col.getPrefWidth();
            out.add(new ColumnState(col.getId(), width, col.isVisible()));
        }
        return out;
    }

    /** 恢复列宽与可见性；未知 id 忽略，非法宽度忽略。 */
    public void applyColumns(List<ColumnState> states) {
        if (states == null || states.isEmpty()) {
            return;
        }
        Map<String, ColumnState> byId = new LinkedHashMap<>();
        for (ColumnState s : states) {
            if (s != null && s.id != null) {
                byId.put(s.id, s);
            }
        }
        for (TableColumn<FileItem, ?> col : getColumns()) {
            if (COL_SNIPPET.equals(col.getId())) {
                continue;
            }
            ColumnState s = byId.get(col.getId());
            if (s == null) {
                continue;
            }
            if (s.width >= MIN_SANE_WIDTH) {
                col.setPrefWidth(s.width);
            }
            col.setVisible(s.visible);
        }
        // 至少保留一列可见，否则表格会变成一片空白且用户找不到恢复入口
        boolean anyVisible = getColumns().stream().anyMatch(TableColumn::isVisible);
        if (!anyVisible && findColumn(COL_NAME) != null) {
            findColumn(COL_NAME).setVisible(true);
        }
    }

    /**
     * 只恢复列的显隐，不用保存的宽度。
     *
     * <p>用于"布局版本落后"的场景：让用户拿到新的默认列宽，同时保留他手动隐藏过的列。
     */
    public void applyColumnVisibility(List<ColumnState> states) {
        if (states == null || states.isEmpty()) {
            return;
        }
        Map<String, ColumnState> byId = new LinkedHashMap<>();
        for (ColumnState s : states) {
            if (s != null && s.id != null) {
                byId.put(s.id, s);
            }
        }
        for (TableColumn<FileItem, ?> col : getColumns()) {
            ColumnState s = byId.get(col.getId());
            if (s != null) {
                col.setVisible(s.visible);
            }
        }
        boolean anyVisible = getColumns().stream().anyMatch(TableColumn::isVisible);
        if (!anyVisible && findColumn(COL_NAME) != null) {
            findColumn(COL_NAME).setVisible(true);
        }
    }

    /**
     * 表头右键菜单：切换列的显示/隐藏。
     *
     * <p>菜单要在皮肤创建、表头节点存在之后才能挂上去，所以放到 {@code layoutChildren} 里一次性安装。
     */
    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        if (headerMenu == null) {
            installHeaderMenu();
        }
    }

    private void installHeaderMenu() {
        ContextMenu menu = new ContextMenu();
        for (TableColumn<FileItem, ?> col : getColumns()) {
            CheckMenuItem item = new CheckMenuItem(col.getText());
            item.selectedProperty().bindBidirectional(col.visibleProperty());
            menu.getItems().add(item);
        }
        javafx.scene.Node header = lookup(".column-header-background");
        if (!(header instanceof Region region)) {
            // 皮肤还没准备好，下次布局再试
            return;
        }
        region.setOnContextMenuRequested(event -> {
            menu.show(region, event.getScreenX(), event.getScreenY());
            event.consume();
        });
        this.headerMenu = menu;
    }

    /**
     * 把表格的剩余宽度补给“名称”列，避免右侧留一条空白。
     *
     * <p>只在差值大于 1px 时才设置，并且用 {@code stretchingNameColumn} 防止重入：
     * 列宽变化会触发布局、布局又可能改变表格宽度，没有这道闸门就会出现抖动甚至死循环。
     *
     * <p>预留 {@link #LAYOUT_RESERVE} 像素是必需的：{@code TableView.getWidth()} 是整表宽度，
     * 而列可用宽度还要减去纵向滚动条与边框。只留 4px 会导致列总宽刚好超出视口，
     * 于是底部冒出一条横向滚动条——既难看又会让布局在“有/无滚动条”之间反复。
     */
    private void stretchNameColumn() {
        if (stretchingNameColumn || nameColumn == null) {
            return;
        }
        double others = 0;
        for (TableColumn<FileItem, ?> col : getColumns()) {
            if (col == nameColumn || !col.isVisible()) {
                continue;
            }
            double width = col.getWidth();
            others += width > 0 ? width : col.getPrefWidth();
        }
        double available = getWidth() - others - LAYOUT_RESERVE;
        if (available < MIN_SANE_WIDTH) {
            return;
        }
        if (Math.abs(nameColumn.getPrefWidth() - available) < 1) {
            return;
        }
        stretchingNameColumn = true;
        try {
            nameColumn.setPrefWidth(available);
        } finally {
            stretchingNameColumn = false;
        }
    }

    // ------------------------------------------------------------ 列定义

    /** 星标列：点击切换收藏。 */
    private TableColumn<FileItem, FileItem> favoriteColumn() {
        TableColumn<FileItem, FileItem> col = new TableColumn<>("★");
        col.setId(COL_FAVORITE);
        col.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        col.setCellFactory(c -> new TableCell<>() {
            private final Label star = new Label();

            {
                // 关键：星标列必须清掉单元格默认的左右内边距，并声明"最小宽度就是首选宽度"。
                // 早先列宽 34px 减去单元格 8px×2 与自身 4px×2 内边距后只剩约 10px，
                // 装不下 15px 的星号，JavaFX 就把文字换成了省略号——看起来像"这一列坏掉了"。
                getStyleClass().add("star-column-cell");
                star.getStyleClass().add("star-cell");
                star.setMinWidth(Region.USE_PREF_SIZE);
                star.setOnMouseClicked(event -> {
                    TableRow<FileItem> row = getTableRow();
                    FileItem item = row == null ? null : row.getItem();
                    if (item != null) {
                        badges.toggleFavorite(item);
                        // 星标不是数据列，切换后必须显式刷新才能立刻看到变化
                        FileTable.this.refresh();
                    }
                    event.consume();
                });
            }

            @Override
            protected void updateItem(FileItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                boolean favorite = badges.isFavorite(item);
                // 两种状态都用同一个实心星号，只用颜色区分。
                // 早先用 ☆（U+2606）表示未收藏，实测在很多字体下渲染得几乎看不见，
                // 用户会以为这一列是坏的空列；同一个字形配不同颜色既省事又不会被字体坑到。
                star.setText("★");
                star.getStyleClass().removeAll("star-on", "star-off");
                star.getStyleClass().add(favorite ? "star-on" : "star-off");
                star.setTooltip(new Tooltip(favorite ? "取消收藏" : "加入收藏"));
                setGraphic(star);
            }
        });
        col.setPrefWidth(44);
        col.setSortable(false);
        col.setStyle("-fx-alignment: CENTER;");
        return col;
    }

    /** 标签列：平铺前几个标签，超出显示 +N。 */
    private TableColumn<FileItem, FileItem> tagsColumn() {
        TableColumn<FileItem, FileItem> col = new TableColumn<>("标签");
        col.setId(COL_TAGS);
        col.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        col.setCellFactory(c -> new TableCell<>() {
            private final HBox box = new HBox(4);

            @Override
            protected void updateItem(FileItem item, boolean empty) {
                super.updateItem(item, empty);
                box.getChildren().clear();
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                List<String> tags = badges.tagsOf(item);
                if (tags.isEmpty()) {
                    setGraphic(null);
                    return;
                }
                int shown = Math.min(MAX_VISIBLE_TAGS, tags.size());
                for (int i = 0; i < shown; i++) {
                    Label chip = new Label(tags.get(i));
                    chip.getStyleClass().add("tag-chip");
                    box.getChildren().add(chip);
                }
                if (tags.size() > shown) {
                    Label more = new Label("+" + (tags.size() - shown));
                    more.getStyleClass().add("tag-chip-more");
                    more.setTooltip(new Tooltip(String.join("、", tags)));
                    box.getChildren().add(more);
                }
                setGraphic(box);
            }
        });
        col.setComparator(java.util.Comparator.comparingInt(
                (FileItem i) -> badges.tagsOf(i).size()));
        col.setPrefWidth(130);
        return col;
    }

    /**
     * 名称列：类型色块 + 文件名。
     *
     * <p>色块是"类型"列的图形化补充——只靠文字列，用户要一行行读才能找到图片；
     * 有了颜色，一扫就能定位到某一类。
     */
    private static TableColumn<FileItem, String> nameColumn() {
        TableColumn<FileItem, String> col = new TableColumn<>("名称");
        col.setId(COL_NAME);
        col.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().name()));
        col.setComparator(FormatUtil.chineseText());
        col.setPrefWidth(300);
        col.setCellFactory(c -> new TableCell<>() {
            private final Region badge = new Region();
            private final javafx.scene.image.ImageView iconView = new javafx.scene.image.ImageView();
            private final Label label = new Label();
            private final HBox box = new HBox(7);

            {
                badge.getStyleClass().add("kind-badge");
                badge.setMinSize(9, 9);
                badge.setPrefSize(9, 9);
                badge.setMaxSize(9, 9);

                // 图标尺寸固定，且不允许拉伸：不同图标原始尺寸不同（缩略图是 40px、
                // 系统图标是 32px），不固定住会让每行的文字起始位置参差不齐
                iconView.setFitWidth(FileIcons.ICON_SIZE);
                iconView.setFitHeight(FileIcons.ICON_SIZE);
                iconView.setPreserveRatio(true);
                iconView.setSmooth(true);

                box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                // 必须给 Label 自己的样式类：JavaFX 的 -fx-text-fill 不会从 TableCell
                // 向下继承到 graphic 里的 Label。漏了这一步，深色主题下文件名会是默认的
                // 黑色印在深色背景上——几乎看不见，而浅色主题下完全正常。
                label.getStyleClass().add("name-label");
                box.getChildren().addAll(badge, iconView, label);
            }

            @Override
            protected void updateItem(String name, boolean empty) {
                super.updateItem(name, empty);
                if (empty || name == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                TableRow<FileItem> row = getTableRow();
                FileItem item = row == null ? null : row.getItem();
                badge.setStyle("-fx-background-color: "
                        + FormatUtil.kindColor(item == null ? null : item.kind()) + ";");

                // 三级降级：缩略图 → 系统图标 → 自带色块。
                // 三者只显示一个，所以这里把"不用的那个"同时隐藏，
                // 否则会出现"色块和图标并排"这种一眼就错的排版。
                //
                // 注意：这里从表格反查图标来源，而不是捕获构造时的字段——
                // 本方法所属的 nameColumn() 是静态方法，拿不到实例字段；
                // 而且 setIcons 可能在构造之后才被调用，运行时反查才是对的。
                FileIcons source = getTableView() instanceof FileTable table ? table.icons() : null;
                javafx.scene.image.Image icon = source == null ? null : source.iconFor(item);
                if (icon == null) {
                    iconView.setImage(null);
                    iconView.setVisible(false);
                    iconView.setManaged(false);
                    badge.setVisible(true);
                    badge.setManaged(true);
                } else {
                    iconView.setImage(icon);
                    iconView.setVisible(true);
                    iconView.setManaged(true);
                    badge.setVisible(false);
                    badge.setManaged(false);
                }

                label.setText(name);
                setGraphic(box);
                setText(null);
            }
        });
        return col;
    }

    /**
     * 内容命中摘要列。
     *
     * <p>只有做内容搜索时才有内容可显示，所以默认隐藏、由 MainWindow 在需要时打开。
     */
    private TableColumn<FileItem, FileItem> snippetColumn() {
        TableColumn<FileItem, FileItem> col = new TableColumn<>("命中内容");
        col.setId(COL_SNIPPET);
        col.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        col.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(FileItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || snippetProvider == null) {
                    setText(null);
                    setTooltip(null);
                    return;
                }
                String snippet = snippetProvider.apply(item);
                setText(snippet == null || snippet.isEmpty() ? "" : snippet);
                setTooltip(snippet == null || snippet.isEmpty() ? null : new Tooltip(snippet));
            }
        });
        col.setPrefWidth(260);
        col.setSortable(false);
        col.setVisible(false);
        return col;
    }

    /** 文本列：展示文本即排序键。 */
    private static TableColumn<FileItem, String> textColumn(
            String id, String title, Function<FileItem, String> extractor, double width) {
        TableColumn<FileItem, String> col = new TableColumn<>(title);
        col.setId(id);
        col.setCellValueFactory(c -> {
            String value = extractor.apply(c.getValue());
            return new ReadOnlyStringWrapper(value == null ? "" : value);
        });
        col.setComparator(FormatUtil.chineseText());
        col.setPrefWidth(width);
        return col;
    }

    private static TableColumn<FileItem, FileItem> typeColumn() {
        TableColumn<FileItem, FileItem> col = new TableColumn<>("类型");
        col.setId(COL_TYPE);
        col.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        col.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(FileItem item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.typeText());
            }
        });
        col.setComparator(java.util.Comparator
                .comparingInt((FileItem i) -> i.kind().ordinal())
                .thenComparing(FileItem::ext, FormatUtil.chineseText()));
        col.setPrefWidth(110);
        return col;
    }

    private static TableColumn<FileItem, Long> sizeColumn() {
        TableColumn<FileItem, Long> col = new TableColumn<>("大小");
        col.setId(COL_SIZE);
        col.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().size()));
        col.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(Long bytes, boolean empty) {
                super.updateItem(bytes, empty);
                if (empty || bytes == null) {
                    setText(null);
                    setTooltip(null);
                    return;
                }
                TableRow<FileItem> row = getTableRow();
                FileItem item = row == null ? null : row.getItem();
                if (item != null && item.directory()) {
                    setText("—");
                    // 单元格会被复用，必须清掉上一行残留的 tooltip
                    setTooltip(null);
                } else {
                    setText(FormatUtil.size(bytes));
                    setTooltip(new Tooltip(FormatUtil.count(bytes) + " 字节"));
                }
            }
        });
        col.setComparator(java.util.Comparator.naturalOrder());
        col.setPrefWidth(90);
        col.setStyle("-fx-alignment: CENTER-RIGHT;");
        return col;
    }

    private static TableColumn<FileItem, FileTime> timeColumn(
            String id, String title, Function<FileItem, FileTime> extractor) {
        TableColumn<FileItem, FileTime> col = new TableColumn<>(title);
        col.setId(id);
        col.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(extractor.apply(c.getValue())));
        col.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(FileTime value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : FormatUtil.time(value));
            }
        });
        col.setComparator(java.util.Comparator.naturalOrder());
        // 148px 是"2026-09-20 15:33"（16 个字符）不被截断所需的宽度。
        // 早先用 125px，实测被截成"2026-09-20 15:..."——截断的时间戳等于没有信息。
        col.setPrefWidth(148);
        return col;
    }
}
