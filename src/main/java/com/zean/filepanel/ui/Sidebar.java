package com.zean.filepanel.ui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 左侧分类栏。
 *
 * <h2>两个刻意的设计</h2>
 * <ol>
 *   <li><b>分类列表稳定、计数动态</b>：分类项在一次扫描期间只构建一次（只收录数量大于 0 的类型），
 *       而计数随搜索实时变化。若分类项本身也跟着搜索变，用户每敲一个字符侧栏就跳一次，
 *       鼠标还没移过去目标已经没了。</li>
 *   <li><b>计数来自“搜索结果”而不是“全部”</b>：这样侧栏展示的是当前搜索结果的类型分布，
 *       用户能一眼看出“这个文件夹里图片有多少张”，而不是永远显示全量统计。</li>
 * </ol>
 */
public class Sidebar extends VBox {

    private final ListView<SidebarCategory> list = new ListView<>();
    private final ObservableList<SidebarCategory> categories = FXCollections.observableArrayList();
    private final Map<String, Long> counts = new HashMap<>();

    private Runnable onSelectionChanged = () -> {
    };

    public Sidebar() {
        getStyleClass().add("sidebar");
        setPrefWidth(190);
        setMinWidth(150);

        Label title = new Label("分类");
        title.getStyleClass().add("sidebar-title");
        title.setPadding(new Insets(10, 12, 6, 12));

        list.setItems(categories);
        list.getStyleClass().add("sidebar-list");
        list.setFixedCellSize(30);
        list.setCellFactory(view -> new CategoryCell());
        list.getSelectionModel().selectedItemProperty()
                .addListener((obs, was, is) -> onSelectionChanged.run());

        VBox.setVgrow(list, Priority.ALWAYS);
        getChildren().addAll(title, list);
    }

    /**
     * 重建分类列表，并尽量保留当前选中项。
     *
     * <p>保留选中项很重要：刷新或换文件夹后如果选中项被重置为“全部”，
     * 用户会觉得自己刚点的分类“被取消了”。
     */
    public void setCategories(List<SidebarCategory> items) {
        String previousId = selectedId();
        categories.setAll(items);
        if (previousId != null) {
            select(previousId);
        }
        if (list.getSelectionModel().getSelectedItem() == null && !categories.isEmpty()) {
            list.getSelectionModel().selectFirst();
        }
        onSelectionChanged.run();
    }

    /** 更新计数并刷新显示。 */
    public void setCounts(Map<String, Long> newCounts) {
        counts.clear();
        if (newCounts != null) {
            counts.putAll(newCounts);
        }
        list.refresh();
    }

    public long countOf(SidebarCategory category) {
        Long c = counts.get(category.id());
        return c == null ? 0L : c;
    }

    public SidebarCategory selected() {
        SidebarCategory item = list.getSelectionModel().getSelectedItem();
        return item == null ? SidebarCategory.all() : item;
    }

    public String selectedId() {
        SidebarCategory item = list.getSelectionModel().getSelectedItem();
        return item == null ? null : item.id();
    }

    public void select(String id) {
        if (id == null) {
            return;
        }
        for (SidebarCategory c : categories) {
            if (c.id().equals(id)) {
                list.getSelectionModel().select(c);
                return;
            }
        }
    }

    public void setOnSelectionChanged(Runnable action) {
        this.onSelectionChanged = action == null ? () -> {
        } : action;
    }

    /** 分类计数快照，供自检断言“侧栏计数与表格行数一致”。 */
    public Map<String, Long> countsSnapshot() {
        return new LinkedHashMap<>(counts);
    }

    /** 一行分类：左侧名称、右侧计数。 */
    private final class CategoryCell extends ListCell<SidebarCategory> {

        private final Label name = new Label();
        private final Label count = new Label();
        private final HBox box = new HBox(6);

        private CategoryCell() {
            name.getStyleClass().add("sidebar-item-name");
            count.getStyleClass().add("sidebar-item-count");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            box.getChildren().addAll(name, spacer, count);
            box.setAlignment(Pos.CENTER_LEFT);
            box.setPadding(new Insets(0, 12, 0, 12));
        }

        @Override
        protected void updateItem(SidebarCategory item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            name.setText(item.label());
            count.setText(FormatUtil.count(countOf(item)));
            setGraphic(box);
        }
    }
}
