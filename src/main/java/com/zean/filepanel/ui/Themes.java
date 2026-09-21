package com.zean.filepanel.ui;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;

/**
 * 主题的应用与传递。
 *
 * <p>主题的实现方式是给场景根节点挂一个 {@code theme-dark} 样式类，
 * 由 CSS 里的 looked-up color 覆盖整棵树的色板。因此"切换主题"是 O(1) 的操作，
 * 不需要遍历任何控件。
 *
 * <p>对话框有<b>自己的</b>场景根（DialogPane），不会继承主窗口的样式类，
 * 所以必须显式把主题与样式表一起传过去。早先几个对话框只传了样式表、
 * 忘了传主题类，结果深色模式下弹出的是一个刺眼的白色对话框。
 * 这个类就是为了让"传主题"这件事只有一个入口，不会再漏。
 */
public final class Themes {

    public static final String LIGHT = "light";
    public static final String DARK = "dark";

    private static final String DARK_CLASS = "theme-dark";

    private Themes() {
    }

    /** 把主题应用到某个节点（通常是场景根）。 */
    public static void apply(Parent root, String theme) {
        if (root == null) {
            return;
        }
        root.getStyleClass().remove(DARK_CLASS);
        if (DARK.equals(theme)) {
            root.getStyleClass().add(DARK_CLASS);
        }
    }

    public static boolean isDark(String theme) {
        return DARK.equals(theme);
    }

    /** 在两种主题间切换。 */
    public static String toggle(String theme) {
        return isDark(theme) ? LIGHT : DARK;
    }

    /** 供按钮文字使用：当前主题下"点一下会切到什么"。 */
    public static String toggleLabel(String theme) {
        return isDark(theme) ? "浅色" : "深色";
    }

    /**
     * 让对话框跟随主窗口的样式表与主题。
     *
     * <p>从 owner 的场景里取样式表；拿不到就退化成"没有任何样式"，
     * 也不会抛异常——对话框能弹出来永远比"样式完美"重要。
     */
    public static void applyToDialog(DialogPane pane, Window owner) {
        if (pane == null) {
            return;
        }
        Scene ownerScene = owner == null ? null : owner.getScene();
        if (ownerScene == null) {
            return;
        }
        pane.getStylesheets().addAll(ownerScene.getStylesheets());
        // 主窗口的根节点上挂着 theme-dark，对话框要跟着挂一份
        if (ownerScene.getRoot() != null
                && ownerScene.getRoot().getStyleClass().contains(DARK_CLASS)) {
            pane.getStyleClass().add(DARK_CLASS);
        }
    }
}
