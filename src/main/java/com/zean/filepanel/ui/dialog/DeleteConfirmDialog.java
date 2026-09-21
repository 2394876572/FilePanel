package com.zean.filepanel.ui.dialog;

import com.zean.filepanel.core.DeletePlan;
import com.zean.filepanel.core.DeletePolicy;
import com.zean.filepanel.core.FileItem;
import com.zean.filepanel.ops.DeleteService;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;
import java.util.Optional;

/**
 * 删除确认对话框（实现设计决策 D4 的界面部分）。
 *
 * <p>三种情况走三条不同的路径，原则是<b>用户永远知道自己正在做什么</b>：
 * <ol>
 *   <li><b>全部可回收</b>：普通确认，附一个「改为彻底删除」的勾选，默认不勾。</li>
 *   <li><b>全部永久删除</b>：红色警示，明确写出"无法从回收站恢复"，并列出将被删除的文件名。</li>
 *   <li><b>混合</b>：不静默按策略执行，而是让用户在
 *       「只删小的（进回收站）」「全部删（其中 N 项永久）」「取消」之间明确选择。
 *       这一条是 D4 里最关键的安全护栏——否则大文件会在用户毫无察觉的情况下被永久删除。</li>
 * </ol>
 */
public final class DeleteConfirmDialog {

    /** 用户的选择。 */
    public record Decision(DeleteService.Mode mode, List<FileItem> items) {
    }

    private DeleteConfirmDialog() {
    }

    /**
     * 弹出确认框。
     *
     * @return 用户确认后的删除请求；取消或关闭窗口时返回空
     */
    public static Optional<Decision> confirm(Window owner, List<FileItem> items, DeletePolicy policy) {
        if (items == null || items.isEmpty()) {
            return Optional.empty();
        }
        DeletePlan plan = policy.plan(items);
        if (plan.isEmpty()) {
            return Optional.empty();
        }

        if (plan.isMixed()) {
            return confirmMixed(owner, plan, policy);
        }
        if (plan.isAllPermanent()) {
            return confirmAllPermanent(owner, plan, policy);
        }
        return confirmAllRecyclable(owner, plan, policy);
    }

    // ------------------------------------------------------------ 全部可回收

    private static Optional<Decision> confirmAllRecyclable(Window owner, DeletePlan plan,
                                                           DeletePolicy policy) {
        CheckBox permanent = new CheckBox("改为彻底删除（不放入回收站，无法恢复）");
        permanent.getStyleClass().add("danger-check");

        VBox content = buildContent(headlineFor(plan, policy), plan.recyclable(), permanent);

        Dialog<ButtonType> dialog = newDialog(owner, "删除", content);
        ButtonType okType = new ButtonType("删除", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okType, ButtonType.CANCEL);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != okType) {
            return Optional.empty();
        }
        DeleteService.Mode mode = permanent.isSelected()
                ? DeleteService.Mode.ALL_PERMANENT
                : DeleteService.Mode.ALL_RECYCLE;
        return Optional.of(new Decision(mode, plan.recyclable()));
    }

    // -------------------------------------------------------- 全部永久删除

    private static Optional<Decision> confirmAllPermanent(Window owner, DeletePlan plan,
                                                          DeletePolicy policy) {
        String text = headlineFor(plan, policy);
        VBox content = buildContent(text, plan.permanent(), null);
        content.getStyleClass().add("danger-box");

        Dialog<ButtonType> dialog = newDialog(owner, "永久删除确认", content);
        ButtonType okType = new ButtonType("永久删除", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okType, ButtonType.CANCEL);
        // 把"永久删除"按钮标成危险操作，避免误点
        javafx.scene.Node okButton = dialog.getDialogPane().lookupButton(okType);
        if (okButton != null) {
            okButton.getStyleClass().add("danger-button");
        }

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != okType) {
            return Optional.empty();
        }
        return Optional.of(new Decision(DeleteService.Mode.ALL_PERMANENT, plan.permanent()));
    }

    // ----------------------------------------------------------------- 混合

    private static Optional<Decision> confirmMixed(Window owner, DeletePlan plan,
                                                   DeletePolicy policy) {
        String text = headlineFor(plan, policy);
        VBox content = buildContent(text, plan.permanent(), null);
        content.getStyleClass().add("danger-box");

        Dialog<ButtonType> dialog = newDialog(owner, "删除确认（含永久删除）", content);
        ButtonType recycleOnly = new ButtonType(
                "仅删除小的 " + plan.recyclable().size() + " 项（进回收站）",
                ButtonBar.ButtonData.OK_DONE);
        ButtonType all = new ButtonType(
                "全部删除（其中 " + plan.permanent().size() + " 项永久删除）",
                ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(recycleOnly, all, ButtonType.CANCEL);

        javafx.scene.Node allButton = dialog.getDialogPane().lookupButton(all);
        if (allButton != null) {
            allButton.getStyleClass().add("danger-button");
        }

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty()) {
            return Optional.empty();
        }
        if (result.get() == recycleOnly) {
            return Optional.of(new Decision(DeleteService.Mode.ALL_RECYCLE, plan.recyclable()));
        }
        if (result.get() == all) {
            return Optional.of(new Decision(DeleteService.Mode.POLICY, plan.items()));
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------ 公共构件

    /**
     * 确认框的正文标题——三种情况三句话。
     *
     * <p>刻意做成<b>纯函数</b>（不碰 JavaFX）：一来三种分支的文案可以被单测逐条钉住，
     * 二来"预览"与"真正弹出来的那个框"共用同一份文案——两处各写一份的话，
     * 迟早会出现"截图里说进回收站、真正删的时候说永久删除"这种最不该有的偏差。
     */
    static String headlineFor(DeletePlan plan, DeletePolicy policy) {
        if (plan.isMixed()) {
            return "选中的 " + plan.total() + " 项中，有 " + plan.permanent().size()
                    + " 项超过 " + policy.describeThreshold() + "。\n"
                    + "请选择处理方式——注意“永久删除”的条目无法从回收站找回。";
        }
        if (plan.isAllPermanent()) {
            return "以下 " + plan.total() + " 项超过 " + policy.describeThreshold()
                    + "，将被【永久删除】且无法从回收站恢复。";
        }
        return "将 " + plan.total() + " 项放入回收站，之后可以从回收站还原。";
    }

    /**
     * 供 {@code --delete-preview} 使用的渲染入口：把确认框画出来，但<b>不弹窗、不阻塞</b>。
     *
     * <p>存在的理由和设置面板/批量创建面板那两张一样：这是本项目里后果最严重的一个界面
     * （D4 的三条安全护栏全在这里），而它只有真的点删除才会出现——单测看不见，
     * {@code --ui-selftest} 也看不见。有了这个入口，它的文案、红字与按钮排布才能被截图留证。
     *
     * <p>渲染的是哪一条分支由传入的条目本身决定（都小于阈值 → 可回收那条），
     * 不做"为了好看假装一个场景"的事。
     */
    public static Parent buildContentForPreview(List<FileItem> items, DeletePolicy policy) {
        DeletePlan plan = policy.plan(items);
        boolean dangerous = plan.isMixed() || plan.isAllPermanent();

        CheckBox extra = null;
        if (!dangerous) {
            extra = new CheckBox("改为彻底删除（不放入回收站，无法恢复）");
            extra.getStyleClass().add("danger-check");
        }

        VBox content = buildContent(headlineFor(plan, policy),
                dangerous ? plan.permanent() : plan.recyclable(), extra);
        if (dangerous) {
            content.getStyleClass().add("danger-box");
        }

        ButtonBar bar = new ButtonBar();
        if (plan.isMixed()) {
            bar.getButtons().addAll(
                    previewButton("仅删除小的 " + plan.recyclable().size() + " 项（进回收站）", false),
                    previewButton("全部删除（其中 " + plan.permanent().size() + " 项永久删除）", true),
                    previewButton("取消", false));
        } else if (plan.isAllPermanent()) {
            bar.getButtons().addAll(previewButton("永久删除", true), previewButton("取消", false));
        } else {
            bar.getButtons().addAll(previewButton("删除", false), previewButton("取消", false));
        }

        VBox root = new VBox(12, content, bar);
        root.setPadding(new Insets(4));
        return root;
    }

    private static Button previewButton(String text, boolean danger) {
        Button button = new Button(text);
        if (danger) {
            button.getStyleClass().add("danger-button");
        }
        return button;
    }

    private static Dialog<ButtonType> newDialog(Window owner, String title, VBox content) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setResizable(true);
        dialog.getDialogPane().setContent(content);
        com.zean.filepanel.ui.Themes.applyToDialog(dialog.getDialogPane(), owner);
        return dialog;
    }

    private static VBox buildContent(String headline, List<FileItem> listed, CheckBox extra) {
        Label head = new Label(headline);
        head.setWrapText(true);
        head.getStyleClass().add("dialog-headline");

        TextArea names = new TextArea(previewNames(listed));
        names.setEditable(false);
        names.setWrapText(false);
        names.getStyleClass().add("details-area");
        names.setPrefRowCount(Math.min(10, Math.max(3, listed.size())));
        names.setPrefColumnCount(64);

        VBox content = new VBox(10, head);
        if (!listed.isEmpty()) {
            content.getChildren().add(names);
        }
        if (extra != null) {
            content.getChildren().add(extra);
        }
        content.setPadding(new Insets(6, 4, 0, 4));
        content.setPrefWidth(560);
        return content;
    }

    /** 最多列出 10 个名字，其余用"…等 N 项"概括，避免对话框被长列表撑爆。 */
    static String previewNames(List<FileItem> items) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(10, items.size());
        for (int i = 0; i < limit; i++) {
            FileItem item = items.get(i);
            sb.append("·   ").append(item.name());
            if (!item.directory()) {
                sb.append("   （").append(com.zean.filepanel.ui.FormatUtil.size(item.size())).append("）");
            }
            sb.append('\n');
        }
        if (items.size() > limit) {
            sb.append("… 等共 ").append(items.size()).append(" 项\n");
        }
        return sb.toString();
    }
}
