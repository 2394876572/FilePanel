package com.zean.filepanel.ui;

import com.zean.filepanel.store.RecentStore;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/**
 * 顶部“最近使用”横向卡片区。
 *
 * <p>这是用户明确要求的功能：把最近操作过的文件展示在前排，
 * 免得每次都要在一百多个文件里重新找昨天刚看过的那份文档。
 *
 * <h2>几个刻意的决定</h2>
 * <ul>
 *   <li><b>可折叠且记住状态</b>：它毕竟占了约 90px 高度，不需要时应能收起来，
 *       而且收起后重开程序不该又自己弹出来。</li>
 *   <li><b>打开失败就给提示，不静默</b>：记录里的文件可能已被移走，
 *       此时卡片仍会显示（用户会想知道"我最近碰过它"），点击才提示文件已不存在。</li>
 *   <li><b>不用鼠标悬停预览等花活</b>：M6 再考虑，先把基础可用性做扎实。</li>
 * </ul>
 */
public class RecentStrip extends VBox {

    /** 卡片展示上限。再多就失去"最近"的意义，也会把横向区域塞满。 */
    public static final int MAX_CARDS = 8;

    private final HBox cards = new HBox(10);
    private final Label emptyLabel = new Label("还没有操作记录——打开或复制路径后，文件会出现在这里");
    private final Button collapseButton = new Button("收起");

    private Consumer<RecentStore.Entry> onOpen = entry -> {
    };
    private Runnable onCollapsedChanged = () -> {
    };
    private boolean collapsed;

    public RecentStrip() {
        getStyleClass().add("recent-strip");
        setPadding(new Insets(8, 14, 10, 14));
        setSpacing(6);

        Label title = new Label("最近使用");
        title.getStyleClass().add("recent-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        collapseButton.getStyleClass().add("recent-collapse");
        collapseButton.setOnAction(e -> setCollapsed(!collapsed));

        HBox header = new HBox(8, title, spacer, collapseButton);
        header.setAlignment(Pos.CENTER_LEFT);

        emptyLabel.getStyleClass().add("recent-empty");

        cards.setAlignment(Pos.CENTER_LEFT);
        cards.setPadding(new Insets(2, 0, 2, 0));

        ScrollPane scroller = new ScrollPane(cards);
        scroller.setFitToHeight(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroller.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.getStyleClass().add("recent-scroller");
        scroller.setPrefHeight(74);
        scroller.setMinHeight(74);

        getChildren().addAll(header, emptyLabel, scroller);
        applyCollapsed();
    }

    /** 更新展示内容。 */
    public void setEntries(List<RecentStore.Entry> entries) {
        cards.getChildren().clear();
        if (entries == null || entries.isEmpty()) {
            emptyLabel.setVisible(true);
            emptyLabel.setManaged(true);
            return;
        }
        emptyLabel.setVisible(false);
        emptyLabel.setManaged(false);

        int limit = Math.min(MAX_CARDS, entries.size());
        for (int i = 0; i < limit; i++) {
            cards.getChildren().add(buildCard(entries.get(i)));
        }
        if (entries.size() > limit) {
            Label more = new Label("还有 " + (entries.size() - limit) + " 条…");
            more.getStyleClass().add("recent-more");
            cards.getChildren().add(more);
        }
    }

    private VBox buildCard(RecentStore.Entry entry) {
        Label name = new Label(entry.name == null ? entry.path : entry.name);
        name.getStyleClass().add("recent-card-name");
        name.setMaxWidth(150);

        Label meta = new Label(FormatUtil.relativeTime(entry.timestamp)
                + "　" + (entry.op == null ? "" : entry.op.label()));
        meta.getStyleClass().add("recent-card-meta");

        VBox card = new VBox(3, name, meta);
        card.getStyleClass().add("recent-card");
        card.setPrefWidth(158);
        card.setMinWidth(158);
        // VBox 没有 setTooltip，必须用 Tooltip.install 挂上去
        Tooltip.install(card, new Tooltip(entry.path
                + "\n累计操作 " + entry.count + " 次\n点击打开"));

        card.setOnMouseClicked(event -> onOpen.accept(entry));
        return card;
    }

    public void setOnOpen(Consumer<RecentStore.Entry> listener) {
        this.onOpen = listener == null ? entry -> {
        } : listener;
    }

    public void setOnCollapsedChanged(Runnable listener) {
        this.onCollapsedChanged = listener == null ? () -> {
        } : listener;
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    /** 用户操作触发的折叠切换：会通知监听者回写配置。 */
    public void setCollapsed(boolean collapsed) {
        setCollapsed(collapsed, true);
    }

    /** 设置折叠状态；{@code notify} 为 false 时用于初始化、不回写配置。 */
    public void setCollapsed(boolean collapsed, boolean notify) {
        if (this.collapsed == collapsed) {
            applyCollapsed();
            return;
        }
        this.collapsed = collapsed;
        applyCollapsed();
        if (notify) {
            onCollapsedChanged.run();
        }
    }

    private void applyCollapsed() {
        boolean expanded = !collapsed;
        boolean showEmpty = expanded && cards.getChildren().isEmpty();
        emptyLabel.setVisible(showEmpty);
        emptyLabel.setManaged(showEmpty);
        for (javafx.scene.Node node : getChildren()) {
            if (node != emptyLabel) {
                node.setVisible(expanded);
                node.setManaged(expanded);
            }
        }
        collapseButton.setText(collapsed ? "展开" : "收起");
        setPadding(collapsed ? new Insets(6, 14, 6, 14) : new Insets(8, 14, 10, 14));
    }
}
