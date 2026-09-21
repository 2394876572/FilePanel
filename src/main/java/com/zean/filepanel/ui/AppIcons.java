package com.zean.filepanel.ui;

import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.net.URL;
import java.util.List;

/**
 * 给窗口设置程序自己的图标。
 *
 * <h2>为什么必须单独做这件事</h2>
 * 打包时 jpackage 把图标嵌进的是 <b>.exe 文件本身</b>（资源管理器里看到的那个），
 * 而**窗口左上角与任务栏**用的是 JavaFX 的 {@code Stage.getIcons()}——
 * 不设置的话就是 JavaFX 的默认图标。所以"exe 有图标"和"窗口有图标"是两件独立的事，
 * 用户看到的窗口图标必须由程序自己设。
 *
 * <h2>为什么放多个尺寸而不是一张大图</h2>
 * 标题栏图标通常只有 16×16（高 DPI 下 32×32）。只给一张 256×256 让系统现场缩放，
 * 小尺寸下会发糊；直接提供对应尺寸的图，系统就能取到"正好那一张"。
 * 这里放了 16 / 32 / 48 / 256 四档。
 *
 * <h2>为什么不用 .ico</h2>
 * JavaFX 的 {@code Image} <b>不支持 ICO 格式</b>（只认 png/jpg/gif/bmp）。
 * 所以图标源是 PNG；`.ico` 只用于 jpackage。
 *
 * <h2>取不到图标怎么办</h2>
 * 静默跳过，绝不抛异常：图标是纯装饰，为了它让程序起不来是不可接受的。
 */
public final class AppIcons {

    /** 与 resources 下实际存在的文件一一对应。缺哪个就跳过哪个，不做假设。 */
    private static final List<Integer> SIZES = List.of(16, 32, 48, 256);

    private static final String PATH_TEMPLATE = "/icon/app-%d.png";

    private AppIcons() {
    }

    /**
     * 把程序图标加到窗口上。
     *
     * @param stage 目标窗口；为 null 时什么都不做
     * @return 实际加载成功并加进去的图标张数（供自检断言）
     */
    public static int applyTo(Stage stage) {
        if (stage == null) {
            return 0;
        }
        int loaded = 0;
        for (Integer size : SIZES) {
            Image image = load(size);
            if (image != null && !image.isError()) {
                stage.getIcons().add(image);
                loaded++;
            }
        }
        return loaded;
    }

    /** 供自检使用：某个尺寸的图标能否从 jar 里读出来。 */
    public static Image load(int size) {
        URL url = AppIcons.class.getResource(String.format(PATH_TEMPLATE, size));
        if (url == null) {
            return null;
        }
        try {
            return new Image(url.toExternalForm());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 供自检使用：资源里声明了哪些尺寸。 */
    public static List<Integer> sizes() {
        return SIZES;
    }
}
