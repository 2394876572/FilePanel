package com.zean.filepanel.ui;

import com.zean.filepanel.core.SearchParser;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 可点击的搜索语法面板。
 *
 * <h2>为什么要有它（而不是只留浮层提示）</h2>
 * 之前语法只写在搜索框的鼠标悬停提示里。对已经知道有这个功能的人来说够用，
 * 但对普通用户等于不存在：<b>你得先知道要看提示，才可能看到语法</b>。
 * 这个面板把它变成"看得见的按钮"——不需要记、不需要打字，点一下就填进搜索框。
 *
 * <h2>为什么不做成一排分类快捷按钮</h2>
 * 侧栏已经有"图片 / 文档 / 最近7天 / 收藏"这些分类入口了。再在搜索框下面摆一排
 * 同样语义的按钮，就成了两套入口做同一件事，迟早出现"侧栏选着图片、按钮没亮"这种
 * 对不上的状态。这里只做<b>语法本身</b>的教学与快捷填入，筛选职责仍然归侧栏。
 *
 * <h2>填入而不是替换</h2>
 * 点一个例子是<b>追加</b>到现有查询后面（用空格分隔）。因为多个条件本来就是"同时满足"，
 * 用户点两下就是两个条件——这比"每次点击都覆盖上一条"更符合他正在组合条件的心智。
 */
public class SearchSyntaxPanel extends VBox {

    private final Consumer<String> onExampleChosen;

    /** 供自检断言：面板里一共几个可点的例子。 */
    private int exampleCount;

    public SearchSyntaxPanel(Consumer<String> onExampleChosen) {
        this.onExampleChosen = onExampleChosen == null ? text -> {
        } : onExampleChosen;

        getStyleClass().add("syntax-panel");
        setSpacing(4);
        setPadding(new Insets(8, 14, 10, 14));

        Label title = new Label("搜索语法（点一下就填进搜索框；多个条件用空格分隔，含义是「同时满足」）");
        title.getStyleClass().add("syntax-title");
        getChildren().add(title);

        for (SearchParser.SyntaxItem item : SearchParser.syntaxItems()) {
            getChildren().add(buildRow(item));
        }
    }

    private HBox buildRow(SearchParser.SyntaxItem item) {
        Label label = new Label(item.label());
        label.getStyleClass().add("syntax-label");
        label.setMinWidth(96);
        label.setPrefWidth(96);
        label.setAlignment(Pos.CENTER_LEFT);

        FlowPane examples = new FlowPane(6, 4);
        for (String example : item.examples()) {
            examples.getChildren().add(exampleButton(example));
        }

        Label tip = new Label(item.tip());
        tip.getStyleClass().add("syntax-tip");
        tip.setWrapText(true);

        HBox row = new HBox(8, label, examples, tip);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(tip, Priority.ALWAYS);
        return row;
    }

    private Button exampleButton(String example) {
        Button button = new Button(example);
        button.getStyleClass().add("syntax-example");
        button.setOnAction(e -> onExampleChosen.accept(example));
        exampleCount++;
        return button;
    }

    /** 供自检断言：面板里可点击例子的数量（为 0 说明语法清单没接上）。 */
    public int exampleCount() {
        return exampleCount;
    }

    /** 供自检断言：面板上出现的语法条目名。 */
    public List<String> labels() {
        List<String> out = new ArrayList<>();
        for (SearchParser.SyntaxItem item : SearchParser.syntaxItems()) {
            out.add(item.label());
        }
        return out;
    }
}
