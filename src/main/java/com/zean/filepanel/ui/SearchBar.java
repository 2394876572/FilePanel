package com.zean.filepanel.ui;

import com.zean.filepanel.core.SearchParser;
import com.zean.filepanel.core.SearchQuery;
import com.zean.filepanel.core.SearchScope;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.function.Consumer;

/**
 * 搜索区：范围下拉 + 搜索框 + 语法面板。
 *
 * <h2>四个要点</h2>
 * <ol>
 *   <li><b>防抖</b>：输入停止 100ms 后才真正过滤。逐字符同步过滤在 10 万条目下会让输入发涩；
 *       100ms 远低于人能感知的延迟，却能把连续输入合并成一次过滤。</li>
 *   <li><b>把解析结果显示出来</b>：右侧提示区用自然语言回显"这条查询在筛什么"。
 *       搜索语法最容易让人迷惑的是"我打的字到底被理解成了什么"，
 *       把理解结果直接显示出来比写一篇帮助文档有用得多。</li>
 *   <li><b>范围下拉</b>：裸关键词只看文件名 / 只按类型 / 只按大小，不用学语法。
 *       显式写出的语法优先于范围（见 {@link SearchScope} 的说明）。</li>
 *   <li><b>解析不了就说出来</b>：范围选"类型"却打了不认识的词时，会照旧按文字搜索，
 *       但提示区会写明"已按普通文字搜索"。静默降级是这类功能最常见的坑。</li>
 * </ol>
 */
public class SearchBar extends VBox {

    /** 防抖时长。取 100ms：足以合并连续输入，又几乎不增加可感知延迟。 */
    private static final Duration DEBOUNCE = Duration.millis(100);

    private final ComboBox<SearchScope> scopeBox = new ComboBox<>();
    private final ComboBox<SearchQuery.Op> sizeOpBox = new ComboBox<>();
    private final TextField field = new TextField();
    private final Button clearButton = new Button("清除");
    private final ToggleButton syntaxToggle = new ToggleButton("语法");
    private final Label hint = new Label();
    private final SearchSyntaxPanel syntaxPanel;

    private final PauseTransition debounce = new PauseTransition(DEBOUNCE);
    private Consumer<String> onQueryChanged = text -> {
    };

    /** 本次解析得到的查询，供外部（主窗口）直接使用，避免二次解析产生不一致。 */
    private SearchQuery currentQuery = SearchQuery.EMPTY;
    private java.util.List<String> currentNotes = java.util.List.of();

    public SearchBar() {
        getStyleClass().add("search-bar");
        setSpacing(0);

        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 14, 8, 14));

        Label icon = new Label("搜索");
        icon.getStyleClass().add("search-label");

        scopeBox.getItems().addAll(SearchScope.values());
        scopeBox.setValue(SearchScope.ALL);
        scopeBox.getStyleClass().add("search-scope");
        scopeBox.setTooltip(new Tooltip("把关键词限制在某个字段里找；显式写出的语法（如 size:>10MB）不受影响"));
        scopeBox.setOnAction(e -> refresh());

        sizeOpBox.getItems().addAll(SearchQuery.Op.values());
        sizeOpBox.setValue(SearchQuery.Op.GT);
        sizeOpBox.getStyleClass().add("search-size-op");
        // 下拉里显示"大于/小于"而不是"GT/LT"：这个控件是给普通用户看的
        sizeOpBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(SearchQuery.Op op) {
                return op == null ? "" : op.label();
            }

            @Override
            public SearchQuery.Op fromString(String s) {
                for (SearchQuery.Op op : SearchQuery.Op.values()) {
                    if (op.label().equals(s)) {
                        return op;
                    }
                }
                return SearchQuery.Op.GT;
            }
        });
        sizeOpBox.setTooltip(new Tooltip("裸关键词按什么方式比大小；写 size:<=100KB 这类语法时不受影响"));
        sizeOpBox.setOnAction(e -> refresh());
        // 只有选了"大小"范围才需要这个选择器，其余时候藏起来，免得占地方也免得让人以为它有用
        sizeOpBox.setVisible(false);
        sizeOpBox.setManaged(false);

        field.getStyleClass().add("search-field");
        field.setPromptText("文件名 / 类型 / 大小…  试试 type:图片 size:>10MB 或 *.docx");
        field.setTooltip(new Tooltip(SearchParser.syntaxHelp()));
        HBox.setHgrow(field, Priority.ALWAYS);

        clearButton.getStyleClass().add("search-clear");
        clearButton.setOnAction(e -> clear());

        syntaxToggle.getStyleClass().add("syntax-toggle");
        syntaxToggle.setTooltip(new Tooltip("展开搜索语法，例子可以点，点一下就填进搜索框"));
        syntaxToggle.setOnAction(e -> setSyntaxPanelVisible(syntaxToggle.isSelected()));

        hint.getStyleClass().add("search-hint");

        row.getChildren().addAll(icon, scopeBox, sizeOpBox, field, clearButton, syntaxToggle, hint);

        syntaxPanel = new SearchSyntaxPanel(this::insertExample);
        setSyntaxPanelVisible(false);

        getChildren().addAll(row, syntaxPanel);

        debounce.setOnFinished(e -> onQueryChanged.accept(field.getText()));
        field.textProperty().addListener((obs, was, is) -> {
            refresh();
            debounce.playFromStart();
        });

        refresh();
    }

    // ------------------------------------------------------------------ 范围

    /** 供自检使用：切换搜索范围（等价于用户在下拉里选）。 */
    public void setScope(SearchScope scope) {
        scopeBox.setValue(scope == null ? SearchScope.ALL : scope);
        refresh();
    }

    public SearchScope scope() {
        return scopeBox.getValue() == null ? SearchScope.ALL : scopeBox.getValue();
    }

    /** 供自检使用：切换"大小"的比较方式。 */
    public void setSizeOperator(SearchQuery.Op op) {
        sizeOpBox.setValue(op == null ? SearchQuery.Op.GT : op);
        refresh();
    }

    // ------------------------------------------------------------- 语法面板

    /** 供自检使用：展开/收起语法面板。 */
    public void setSyntaxPanelVisible(boolean visible) {
        syntaxToggle.setSelected(visible);
        syntaxPanel.setVisible(visible);
        syntaxPanel.setManaged(visible);
    }

    public boolean syntaxPanelVisible() {
        return syntaxPanel.isVisible();
    }

    /** 供自检断言：面板里可点击例子的数量。 */
    public int syntaxExampleCount() {
        return syntaxPanel.exampleCount();
    }

    /**
     * 点例子后填进搜索框。
     *
     * <p>是追加而不是覆盖：多个条件本来就允许并存，用户点两下就是两个条件。
     * 并且填完把光标放到末尾、并立刻派发一次，用户马上能看到结果变化。
     */
    private void insertExample(String example) {
        String existing = field.getText() == null ? "" : field.getText().trim();
        String next = existing.isEmpty() ? example : existing + " " + example;
        field.setText(next);
        field.positionCaret(next.length());
        dispatchNow();
        field.requestFocus();
    }

    // ---------------------------------------------------------------- 基础

    /** 立即派发当前输入（跳过防抖），供自检使用。 */
    public void dispatchNow() {
        debounce.stop();
        onQueryChanged.accept(field.getText());
    }

    public String getText() {
        return field.getText();
    }

    public void setText(String text) {
        field.setText(text == null ? "" : text);
        refresh();
    }

    public void clear() {
        field.setText("");
        dispatchNow();
        field.requestFocus();
    }

    /** 把焦点移到搜索框（Ctrl+F）。 */
    public void focusSearch() {
        field.requestFocus();
        field.selectAll();
    }

    public void setOnQueryChanged(Consumer<String> listener) {
        this.onQueryChanged = listener == null ? text -> {
        } : listener;
    }

    /** 供自检断言：当前提示文本。 */
    public String hintText() {
        return hint.getText();
    }

    /**
     * 当前查询（已按范围解析）。
     *
     * <p>主窗口用它而不是自己再 {@code parse} 一次原文：两处各解析一次，
     * 一旦范围参数不一致，就会出现"提示说按文件名、实际按路径搜"这种最难查的偏差。
     */
    public SearchQuery currentQuery() {
        return currentQuery;
    }

    /** 当前解析过程中产生的说明（例如"不是已知类型，已按普通文字搜索"）。 */
    public java.util.List<String> currentNotes() {
        return currentNotes;
    }

    /** 重新解析并更新提示。任何影响解析的输入（文字、范围、大小比较方式）变化都要走这里。 */
    private void refresh() {
        boolean sizeScope = scope() == SearchScope.SIZE;
        sizeOpBox.setVisible(sizeScope);
        sizeOpBox.setManaged(sizeScope);

        String raw = field.getText() == null ? "" : field.getText();
        if (raw.isBlank()) {
            currentQuery = SearchQuery.EMPTY;
            currentNotes = java.util.List.of();
            hint.setText("");
            return;
        }

        SearchParser.ParseResult result =
                SearchParser.parseWithNotes(raw, scope(), sizeOpBox.getValue());
        currentQuery = result.query();
        currentNotes = result.notes();

        StringBuilder sb = new StringBuilder();
        String described = currentQuery.describe();
        if (!described.isEmpty()) {
            sb.append("→ ").append(described);
        }
        // 范围生效时要明确说出来："选了类型"和"真的按类型筛了"必须能被用户区分
        if (scope() != SearchScope.ALL) {
            if (sb.length() > 0) {
                sb.append("　·　");
            }
            sb.append("范围：").append(scope().label());
        }
        for (String note : currentNotes) {
            sb.append("　⚠ ").append(note);
        }
        hint.setText(sb.toString());
    }
}
