package com.zean.filepanel.win;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.WinDef.HICON;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 取 Windows 的<b>真实文件类型图标</b>（M8-b PoC）。
 *
 * <h2>为什么按扩展名取，而不是按文件取</h2>
 * 资源管理器显示的就是"这类文件的图标"：所有 .docx 长得一样，所有 .png 长得一样。
 * 按扩展名取有两个好处：
 * <ol>
 *   <li><b>快</b>：用 {@code SHGFI_USEFILEATTRIBUTES} 时 Shell 不碰磁盘，
 *       只查注册表里该扩展名的关联，因此可以拿一个假路径 {@code x.docx} 去问；</li>
 *   <li><b>可缓存</b>：十几张图标就够了，缓存命中率极高。</li>
 * </ol>
 * 代价是"每个文件的个性化图标"（例如 .exe 各自的图标）拿不到。这是刻意的取舍：
 * 那需要真的打开每个文件，一屏 30 行就是 30 次磁盘+PE 解析，滚动会明显发涩。
 *
 * <h2>这一层为什么不直接返回 JavaFX 的 Image</h2>
 * 本包（{@code win}）只做系统调用，返回<b>裸像素</b>；把它变成 JavaFX 图片是界面层的事。
 * 这样 {@code win} 不依赖 JavaFX，图标提取逻辑也能在没有图形工具包的情况下被调用与排查。
 *
 * <h2>最容易漏的一步：图标句柄必须销毁</h2>
 * {@code SHGetFileInfo} 返回的 {@code HICON} 是<b>调用方拥有</b>的 GDI 资源，
 * 用完必须 {@code DestroyIcon}。不销毁的话每取一次就泄漏一个 GDI 句柄，
 * 而 Windows 每进程的 GDI 句柄上限只有 1 万个——列表滚动一会儿就会开始画不出图标，
 * 且整个过程不报任何错。这类"泄漏不报错"的问题正是本项目的重点防范对象。
 */
public final class ShellIcons {

    // ---- SHGetFileInfo 标志位 -------------------------------------------------
    private static final int SHGFI_ICON = 0x000000100;
    private static final int SHGFI_LARGEICON = 0x000000000;
    private static final int SHGFI_SMALLICON = 0x000000001;
    private static final int SHGFI_USEFILEATTRIBUTES = 0x000000010;
    private static final int FILE_ATTRIBUTE_NORMAL = 0x00000080;

    private static final int DIB_RGB_COLORS = 0;
    private static final int BI_RGB = 0;
    private static final int ICONINFO_SIZE = 8 + 4 + 4 + 8 + 8;

    /** 每个扩展名只查一次。键是"扩展名+尺寸"，值是像素或"取不到"的占位。 */
    private static final Map<String, Icon> CACHE = new LinkedHashMap<>();

    /** 取不到图标的扩展名也记下来，避免每次都去问一遍 Shell。 */
    private static final java.util.Set<String> FAILED = new java.util.HashSet<>();

    private static Boolean available;

    private ShellIcons() {
    }

    /**
     * 一张图标的裸像素。
     *
     * @param width  宽
     * @param height 高
     * @param argb   逐像素的非预乘 ARGB（与 JavaFX {@code PixelFormat.getIntArgbInstance()} 一致）
     */
    public record Icon(int width, int height, int[] argb) {
        public boolean isEmpty() {
            return width <= 0 || height <= 0 || argb == null || argb.length < width * height;
        }
    }

    /** 本机是否能用 Shell 取图标。为 false 时调用方应当降级到自带色块。 */
    public static boolean available() {
        if (available == null) {
            try {
                // 用必定存在的 shell32 试一次；失败（例如被沙箱拦住）就整体降级
                Icon probe = iconForExtension("txt", false);
                available = probe != null && !probe.isEmpty();
            } catch (Throwable t) {
                available = false;
            }
        }
        return available;
    }

    /**
     * 取某个扩展名的图标。
     *
     * @param extension 扩展名，不含点，大小写不敏感
     * @param large     true 取大图标、false 取小图标。
     *                  <b>实测本机 large 返回的是 48×48 而不是文档常说的 32×32</b>——
     *                  实际尺寸由系统的大图标设置决定，所以不要对尺寸做假设，
     *                  一律读 {@link Icon#width()}。
     * @return 图标像素；取不到时返回 null（调用方降级，不抛异常）
     */
    public static Icon iconForExtension(String extension, boolean large) {
        String ext = extension == null ? "" : extension.trim().toLowerCase(Locale.ROOT);
        if (ext.startsWith(".")) {
            ext = ext.substring(1);
        }
        String key = ext + "|" + (large ? "L" : "S");
        if (CACHE.containsKey(key)) {
            return CACHE.get(key);
        }
        if (FAILED.contains(key)) {
            return null;
        }
        Icon icon = extract(ext, large);
        if (icon == null || icon.isEmpty()) {
            FAILED.add(key);
            return null;
        }
        CACHE.put(key, icon);
        return icon;
    }

    /** 供自检/测试观察。 */
    public static int cachedCount() {
        return CACHE.size();
    }

    public static void clearCache() {
        CACHE.clear();
        FAILED.clear();
    }

    // --------------------------------------------------------------- 实际提取

    private static Icon extract(String extension, boolean large) {
        // 整个提取过程包在 try/catch 里：这个函数会被表格<b>每一行</b>调用，
        // 任何一步失败（DLL 里找不到函数、结构体对齐异常、被安全软件拦住）
        // 都必须变成"这张图标取不到"，而绝不能让异常冒到界面渲染路径上。
        // 实测踩过一次：GetIconInfo 写错 DLL 时抛 UnsatisfiedLinkError，
        // 直接把 --icon-sheet 这条命令打死了。
        try {
            // 假路径：配合 SHGFI_USEFILEATTRIBUTES，Shell 只看扩展名、不碰磁盘
            String fake = "x" + (extension.isEmpty() ? "" : "." + extension);
            SHFILEINFOW info = new SHFILEINFOW();
            int flags = SHGFI_ICON | SHGFI_USEFILEATTRIBUTES
                    | (large ? SHGFI_LARGEICON : SHGFI_SMALLICON);
            int rc = Shell32Ex.INSTANCE.SHGetFileInfoW(new WString(fake), FILE_ATTRIBUTE_NORMAL,
                    info, info.size(), flags);
            if (rc == 0 || info.hIcon == null) {
                return null;
            }
            try {
                return pixelsOf(info.hIcon);
            } finally {
                // 必须销毁：否则每取一次泄漏一个 GDI 句柄（见类注释）
                User32Ex.INSTANCE.DestroyIcon(info.hIcon);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * HICON → ARGB 像素。
     *
     * <p>步骤：{@code GetIconInfo} 拆出颜色位图 → 建兼容 DC → 用 {@code GetDIBits}
     * 以 32 位自顶向下读出来 → 释放三个句柄（颜色位图、掩码位图、DC）。
     *
     * <p><b>关于透明度</b>：现代图标（Win10 的 shell32 图标）颜色位图自带 alpha，
     * 直接可用。老式的 16 色图标没有 alpha，透明度只存在于掩码位图里，
     * 这时本实现会选择"整块不透明"——那种图标会显示成一个方块。
     * 这是 PoC 阶段刻意留下的简化：先确认主流格式能用，老格式的表现由
     * {@code --icon-sheet} 的输出如实暴露出来，再决定是否补掩码解码。
     */
    private static Icon pixelsOf(HICON hIcon) {
        ICONINFO iconInfo = new ICONINFO();
        if (!User32Ex.INSTANCE.GetIconInfo(hIcon, iconInfo)) {
            return null;
        }
        Pointer hdc = null;
        try {
            if (iconInfo.hbmColor == null) {
                return null;
            }
            int[] size = bitmapSize(iconInfo.hbmColor);
            int width = size[0];
            int height = size[1];
            if (width <= 0 || height <= 0) {
                return null;
            }

            hdc = Gdi32Ex.INSTANCE.CreateCompatibleDC(null);
            if (hdc == null) {
                return null;
            }

            BITMAPINFO info = new BITMAPINFO();
            info.bmiHeader.biSize = 40;
            info.bmiHeader.biWidth = width;
            // 负高度 = 自顶向下：这样第 0 行就是图像顶部，省掉一次上下翻转
            info.bmiHeader.biHeight = -height;
            info.bmiHeader.biPlanes = 1;
            info.bmiHeader.biBitCount = 32;
            info.bmiHeader.biCompression = BI_RGB;
            info.bmiColors = new int[1];

            // 用连续内存而不是 int[] 自动转换：JNA 对结构体里的数组指针处理更明确
            int[] pixels = new int[width * height];
            Memory buffer = new Memory((long) width * height * 4);
            buffer.clear();
            int lines = Gdi32Ex.INSTANCE.GetDIBits(hdc, iconInfo.hbmColor, 0, height,
                    buffer, info, DIB_RGB_COLORS);
            if (lines == 0) {
                return null;
            }
            for (int i = 0; i < pixels.length; i++) {
                // GetDIBits 按 BGRA 字节序写入，按小端读成 int 正好是 0xAARRGGBB
                pixels[i] = buffer.getInt((long) i * 4);
            }
            if (!hasAnyAlpha(pixels)) {
                // 老式图标没有 alpha 通道：退化为不透明，至少能看出形状与颜色
                for (int i = 0; i < pixels.length; i++) {
                    pixels[i] |= 0xFF000000;
                }
            }
            return new Icon(width, height, pixels);
        } catch (Throwable t) {
            // 任何一步失败都当作"这张图标取不到"，交给调用方降级
            return null;
        } finally {
            if (hdc != null) {
                Gdi32Ex.INSTANCE.DeleteDC(hdc);
            }
            if (iconInfo.hbmColor != null) {
                Gdi32Ex.INSTANCE.DeleteObject(iconInfo.hbmColor);
            }
            if (iconInfo.hbmMask != null) {
                Gdi32Ex.INSTANCE.DeleteObject(iconInfo.hbmMask);
            }
        }
    }

    private static boolean hasAnyAlpha(int[] pixels) {
        for (int p : pixels) {
            if ((p & 0xFF000000) != 0) {
                return true;
            }
        }
        return false;
    }

    /** 从位图句柄读出宽高（{@code BITMAP} 结构的前两个 LONG 恰好是宽与高）。 */
    private static int[] bitmapSize(Pointer hBitmap) {
        Memory memory = new Memory(32);
        memory.clear();
        int written = Gdi32Ex.INSTANCE.GetObjectW(hBitmap, 32, memory);
        if (written <= 0) {
            return new int[]{0, 0};
        }
        // BITMAP: LONG bmType; LONG bmWidth; LONG bmHeight; ...
        return new int[]{memory.getInt(4), memory.getInt(8)};
    }

    // -------------------------------------------------------------- JNA 声明

    /** 供图标提取使用的最小 shell32 绑定（与 {@link Shell32} 分开，避免把两件事混在一起）。 */
    interface Shell32Ex extends StdCallLibrary {
        Shell32Ex INSTANCE = Native.load("shell32", Shell32Ex.class,
                W32APIOptions.UNICODE_OPTIONS);

        int SHGetFileInfoW(WString path, int fileAttributes, SHFILEINFOW info, int size, int flags);
    }

    interface User32Ex extends StdCallLibrary {
        User32Ex INSTANCE = Native.load("user32", User32Ex.class, W32APIOptions.UNICODE_OPTIONS);

        boolean DestroyIcon(HICON icon);

        /**
         * 拆解 HICON。
         *
         * <p><b>注意它在 user32 而不是 gdi32</b>：名字看着像 GDI 函数，实测写成 gdi32 会抛
         * {@code UnsatisfiedLinkError: Error looking up function 'GetIconInfo'}。
         * 这类"库放错了"的错误在编译期完全看不出来，只有真的调用才暴露。
         */
        boolean GetIconInfo(HICON icon, ICONINFO info);
    }

    interface Gdi32Ex extends StdCallLibrary {
        Gdi32Ex INSTANCE = Native.load("gdi32", Gdi32Ex.class, W32APIOptions.UNICODE_OPTIONS);

        Pointer CreateCompatibleDC(Pointer hdc);

        boolean DeleteDC(Pointer hdc);

        boolean DeleteObject(Pointer handle);

        int GetObjectW(Pointer handle, int size, Pointer out);

        int GetDIBits(Pointer hdc, Pointer hBitmap, int start, int lines, Pointer bits,
                      BITMAPINFO info, int usage);
    }

    /** {@code SHFILEINFOW}。字段顺序与大小必须与 SDK 一致，否则读到的是垃圾且不报错。 */
    public static class SHFILEINFOW extends com.sun.jna.Structure {
        /** 用 {@link HICON}（PointerType）而不是裸 Pointer：GDI 那几个函数要的就是 HICON。 */
        public HICON hIcon;
        public int iIcon;
        public int dwAttributes;
        public char[] szDisplayName = new char[260];
        public char[] szTypeName = new char[80];

        @Override
        protected java.util.List<String> getFieldOrder() {
            return java.util.List.of("hIcon", "iIcon", "dwAttributes", "szDisplayName", "szTypeName");
        }
    }

    /** {@code ICONINFO}：fIcon + 热点 + 颜色位图 + 掩码位图（共 32 字节，64 位下带对齐）。 */
    public static class ICONINFO extends com.sun.jna.Structure {
        public boolean fIcon;
        public int xHotspot;
        public int yHotspot;
        public Pointer hbmMask;
        public Pointer hbmColor;

        @Override
        protected java.util.List<String> getFieldOrder() {
            return java.util.List.of("fIcon", "xHotspot", "yHotspot", "hbmMask", "hbmColor");
        }
    }

    /** {@code BITMAPINFOHEADER} + 一个调色板项。 */
    public static class BITMAPINFO extends com.sun.jna.Structure {
        public BITMAPINFOHEADER bmiHeader = new BITMAPINFOHEADER();
        public int[] bmiColors;

        @Override
        protected java.util.List<String> getFieldOrder() {
            return java.util.List.of("bmiHeader", "bmiColors");
        }
    }

    public static class BITMAPINFOHEADER extends com.sun.jna.Structure {
        public int biSize;
        public int biWidth;
        public int biHeight;
        public short biPlanes;
        public short biBitCount;
        public int biCompression;
        public int biSizeImage;
        public int biXPelsPerMeter;
        public int biYPelsPerMeter;
        public int biClrUsed;
        public int biClrImportant;

        @Override
        protected java.util.List<String> getFieldOrder() {
            return java.util.List.of("biSize", "biWidth", "biHeight", "biPlanes", "biBitCount",
                    "biCompression", "biSizeImage", "biXPelsPerMeter", "biYPelsPerMeter",
                    "biClrUsed", "biClrImportant");
        }
    }

    /** 供自检使用：{@code ICONINFO} 的声明大小（64 位下应为 32，用来发现字段顺序/对齐写错）。 */
    public static int iconInfoSize() {
        return new ICONINFO().size();
    }
}
