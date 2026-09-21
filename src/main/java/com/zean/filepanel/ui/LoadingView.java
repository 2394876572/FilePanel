package com.zean.filepanel.ui;

import com.zean.filepanel.core.ScanProgress;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.nio.file.Path;

/**
 * 首屏加载视图。
 *
 * <p>用户明确要求的这句话必须原样出现在这里：
 * <b>「正在读取当前文件夹及子文件夹的文件信息」</b>。
 *
 * <p>进度是<b>真实</b>的（已扫描计数 + 当前正在处理的位置），不是无限循环的假动画。
 * 理由：扫描一个 12 层深、上千文件的目录时，用户需要判断“是在正常工作还是卡死了”，
 * 而一个永远转圈的指示器提供不了这个信息；同时提供取消按钮，避免用户被迫等待。
 */
public class LoadingView extends VBox {

    /** 用户要求的提示文案，不允许改写。 */
    public static final String TITLE_TEXT = "正在读取当前文件夹及子文件夹的文件信息";

    private final Label detailLabel = new Label();
    private final Label hintLabel = new Label();
    private final Button cancelButton = new Button("取消");

    public LoadingView() {
        setAlignment(Pos.CENTER);
        setSpacing(10);
        setPadding(new Insets(40));
        getStyleClass().add("loading-view");

        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(56, 56);
        spinner.getStyleClass().add("loading-spinner");

        Label titleLabel = new Label(TITLE_TEXT);
        titleLabel.getStyleClass().add("loading-title");

        detailLabel.getStyleClass().add("loading-detail");
        detailLabel.setWrapText(false);

        hintLabel.getStyleClass().add("loading-hint");

        HBox buttons = new HBox(cancelButton);
        buttons.setAlignment(Pos.CENTER);
        buttons.setPadding(new Insets(10, 0, 0, 0));

        getChildren().addAll(spinner, titleLabel, detailLabel, hintLabel, buttons);
        reset();
    }

    /** 回到初始态，供“刷新/切换文件夹”后复用。 */
    public void reset() {
        detailLabel.setText("正在准备…");
        hintLabel.setText("");
        cancelButton.setDisable(false);
    }

    /** 由扫描线程节流后的进度回调（已在 UI 线程上执行）更新界面。 */
    public void update(ScanProgress progress, Path root) {
        String scanned = "已扫描 " + FormatUtil.count(progress.total()) + " 项"
                + "（文件 " + FormatUtil.count(progress.files())
                + " · 文件夹 " + FormatUtil.count(progress.dirs()) + "）";
        if (progress.excluded() > 0) {
            scanned += " · 已跳过 " + FormatUtil.count(progress.excluded()) + " 项";
        }
        detailLabel.setText(scanned);
        hintLabel.setText(describeCurrent(progress.currentPath(), root));
    }

    /** 标记为已取消，让用户明确知道“不是失败，是我自己停的”。 */
    public void markCancelled() {
        detailLabel.setText("已取消，正在展示已扫描到的部分…");
        cancelButton.setDisable(true);
    }

    /** 路径较长时转为相对路径，信息量更高也更易读。 */
    private static String describeCurrent(String currentPath, Path root) {
        if (currentPath == null || currentPath.isEmpty()) {
            return "";
        }
        String shown = currentPath;
        if (root != null) {
            try {
                Path rel = root.relativize(Path.of(currentPath));
                if (!rel.toString().isEmpty() && !rel.toString().startsWith("..")) {
                    shown = rel.toString();
                }
            } catch (RuntimeException ignored) {
                // 路径无法相对化时直接用原值
            }
        }
        return "当前：" + shown;
    }

    public void setOnCancel(Runnable action) {
        cancelButton.setOnAction(e -> action.run());
    }

    public void disableCancel() {
        cancelButton.setDisable(true);
    }
}
