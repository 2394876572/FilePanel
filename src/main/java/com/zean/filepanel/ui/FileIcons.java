package com.zean.filepanel.ui;

import com.zean.filepanel.core.FileItem;
import com.zean.filepanel.core.LruCache;
import com.zean.filepanel.win.ShellIcons;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 表格里每行左侧那个小图标的来源（M8）。
 *
 * <h2>三种来源，按优先级降级</h2>
 * <ol>
 *   <li><b>图片缩略图</b>：对图片文件，异步解码成小图。这是"一眼认出是哪张图"的关键，
 *       纯看文件名是做不到的。</li>
 *   <li><b>系统真实图标</b>：非图片文件用 Windows 自己的类型图标
 *       （Word 文档是 Word 图标、Excel 是 Excel 图标），比自绘色块好认得多。</li>
 *   <li><b>自带色块</b>：上面两条都拿不到时（取不到系统图标、图片解码失败、
 *       文件太大、目录等），由表格继续用原来的彩色方块。</li>
 * </ol>
 * 三级降级是刻意的：任何一个来源在别的机器上都可能不可用，
 * 而<b>界面绝不能因为"图标取不到"就变成空白</b>。
 *
 * <h2>两条性能规则</h2>
 * <ul>
 *   <li><b>缩略图必须异步</b>：用 JavaFX {@code Image} 的后台加载（构造时最后两个参数）。
 *       同步解码一张 4000×3000 的照片要几百毫秒，一屏 30 行就是十几秒的界面假死。</li>
 *   <li><b>必须有上限</b>：见 {@link LruCache}。解码后的位图很占内存，没有上限就是内存泄漏。</li>
 * </ul>
 *
 * <h2>为什么缓存键要带"大小 + 修改时间"</h2>
 * 图片被编辑后路径不变。只用路径当键，用户会一直看到旧缩略图，
 * 而且他会以为"程序显示错了"——这与内容索引那边用指纹而非路径是同一个理由。
 */
public final class FileIcons {

    /** 图标显示边长（像素）。16 太小、32 又太占行高，18~20 与当前行高最搭。 */
    public static final int ICON_SIZE = 18;

    /** 缩略图解码的目标边长。按显示尺寸的 2 倍取，兼顾清晰度与解码开销。 */
    private static final int THUMB_DECODE_SIZE = 40;

    /** 缩略图缓存条数上限。一张 40×40 的位图约 6 KB，400 条约 2.5 MB。 */
    public static final int MAX_THUMBNAILS = 400;

    /** 超过这个体积的图片不做缩略图：解码它只为显示 18px，代价与收益不成比例。 */
    public static final long MAX_THUMBNAIL_FILE_BYTES = 40L * 1024 * 1024;

    /** 会尝试做缩略图的扩展名。刻意只放常见格式，不做"碰运气解码"。 */
    private static final Set<String> THUMBNAIL_EXTENSIONS =
            Set.of("png", "jpg", "jpeg", "gif", "bmp", "webp");

    private final LruCache<String, Image> thumbnails = new LruCache<>(MAX_THUMBNAILS);
    private final Map<String, Image> systemIcons = new HashMap<>();

    /** 正在加载中的键，避免同一张图被重复发起加载。 */
    private final Set<String> loading = new java.util.HashSet<>();

    /** 缩略图解码完成后回调（由表格刷新自己）。 */
    private Runnable onReady = () -> {
    };

    /** 系统图标是否可用；为 false 时表格会一直用自带色块。 */
    private final boolean systemIconsAvailable = ShellIcons.available();

    public void setOnReady(Runnable onReady) {
        this.onReady = onReady == null ? () -> {
        } : onReady;
    }

    public boolean systemIconsAvailable() {
        return systemIconsAvailable;
    }

    public int thumbnailCount() {
        return thumbnails.size();
    }

    public long thumbnailEvictions() {
        return thumbnails.evictions();
    }

    /**
     * 取某个条目要显示的图标。
     *
     * @return 可以直接放进 {@code ImageView} 的图片；为 null 时调用方应当退回自带色块
     */
    public Image iconFor(FileItem item) {
        if (item == null) {
            return null;
        }
        if (isThumbnailable(item)) {
            Image thumb = thumbnail(item);
            if (thumb != null) {
                return thumb;
            }
            // 缩略图还在解码中（或解码失败）：先给类型图标，别让这一行空着
        }
        return systemIcon(item);
    }

    /** 这个条目是否属于"该做缩略图"的图片。纯逻辑，便于单测。 */
    public static boolean isThumbnailable(FileItem item) {
        if (item == null || item.directory()) {
            return false;
        }
        if (!THUMBNAIL_EXTENSIONS.contains(item.ext())) {
            return false;
        }
        // 大小未知（0）时也放行：目录之外的条目通常都有真实大小，
        // 而 0 往往表示"还没取到"，此时拒绝反而会让缩略图永远不出现
        return item.size() <= 0 || item.size() <= MAX_THUMBNAIL_FILE_BYTES;
    }

    /**
     * 缩略图缓存键：路径 + 大小 + 修改时间。
     *
     * <p>带指纹是为了让"图片被改过"这件事能反映到界面上（见类注释）。
     */
    public static String thumbnailKey(FileItem item) {
        long modified = item.modified() == null ? 0L : item.modified().toMillis();
        return item.path().toAbsolutePath().normalize() + "|" + item.size() + "|" + modified;
    }

    /** 取缩略图；没缓存就发起一次后台解码并返回 null（下次刷新时就有了）。 */
    private Image thumbnail(FileItem item) {
        String key = thumbnailKey(item);
        Image cached = thumbnails.get(key);
        if (cached != null) {
            return cached;
        }
        if (loading.contains(key)) {
            return null;
        }
        Path path = item.path();
        if (!Files.isReadable(path)) {
            return null;
        }
        loading.add(key);
        try {
            // 后三个参数：按尺寸解码、保持比例、平滑缩放；最后一个 true 是"后台加载"，
            // 这是不阻塞界面的关键
            Image image = new Image(path.toUri().toString(), THUMB_DECODE_SIZE,
                    THUMB_DECODE_SIZE, true, true, true);
            if (image.isError()) {
                loading.remove(key);
                return null;
            }
            image.progressProperty().addListener((obs, was, now) -> {
                if (now != null && now.doubleValue() >= 1.0) {
                    loading.remove(key);
                    if (!image.isError()) {
                        thumbnails.put(key, image);
                    }
                    onReady.run();
                }
            });
            return null;
        } catch (RuntimeException e) {
            // 解码不了（文件损坏、格式不支持）就当没有缩略图，绝不让它影响列表渲染
            loading.remove(key);
            return null;
        }
    }

    /** 取系统图标（按扩展名缓存），转成 JavaFX 图片。 */
    private Image systemIcon(FileItem item) {
        if (!systemIconsAvailable) {
            return null;
        }
        String ext = item.directory() ? "folder" : item.ext();
        String key = ext.toLowerCase(Locale.ROOT);
        if (systemIcons.containsKey(key)) {
            return systemIcons.get(key);
        }
        ShellIcons.Icon icon = ShellIcons.iconForExtension(key, true);
        Image image = icon == null ? null : toImage(icon);
        // 取不到也记成 null 占位？不：HashMap 允许 null 值，但那样每次都要重新问一遍 Shell。
        // 这里用"只在成功时写入"的策略——失败的扩展名由 ShellIcons 自己记住，不会重复查。
        if (image != null) {
            systemIcons.put(key, image);
        }
        return image;
    }

    /** 裸像素 → JavaFX 图片。 */
    static Image toImage(ShellIcons.Icon icon) {
        if (icon == null || icon.isEmpty()) {
            return null;
        }
        WritableImage image = new WritableImage(icon.width(), icon.height());
        image.getPixelWriter().setPixels(0, 0, icon.width(), icon.height(),
                PixelFormat.getIntArgbInstance(), icon.argb(), 0, icon.width());
        return image;
    }

    /**
     * 供自检使用：直接把一张图片路径解成图标，用于验证"缩略图这条链路真的通了"。
     *
     * <p>刻意做成同步：自检要的是确定性结论，而不是"等一会儿再来看看有没有"。
     */
    public Image loadThumbnailSynchronously(Path path) {
        if (path == null || !Files.isReadable(path)) {
            return null;
        }
        try {
            Image image = new Image(path.toUri().toString(), THUMB_DECODE_SIZE,
                    THUMB_DECODE_SIZE, true, true, false);
            return image.isError() ? null : image;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 供自检使用：把某个扩展名的系统图标转成图片（不走缓存）。 */
    public Image systemIconFor(String extension) {
        ShellIcons.Icon icon = ShellIcons.iconForExtension(extension, true);
        return icon == null ? null : toImage(icon);
    }

    public void clear() {
        thumbnails.clear();
        systemIcons.clear();
        loading.clear();
    }
}
