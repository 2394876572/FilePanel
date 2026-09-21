package com.zean.filepanel.win;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * Windows Shell 的 JNA 绑定（当前只用到 {@code SHFileOperationW}）。
 *
 * <p>为什么不用 {@code java.awt.Desktop} 或 Java NIO 删除：
 * 把文件放进<b>回收站</b>是 Shell 的能力，Java 标准库没有对应 API。
 * NIO 的 {@code Files.delete} 是永久删除，误删不可恢复，不能作为默认行为。
 *
 * <h2>关键参数：FOF_WANTNUKEWARNING</h2>
 * 我们同时设置了 {@code FOF_NOCONFIRMATION}（不弹确认框）与 {@code FOF_ALLOWUNDO}（进回收站）。
 * 问题在于：当文件大到回收站装不下时，Windows 会退化为<b>永久删除</b>；
 * 若此时 NOCONFIRMATION 完全生效，用户就会在没有任何提示的情况下丢失文件——
 * 这正是 D4 最担心的“静默永久删除”。
 *
 * <p>解法是加上 {@code FOF_WANTNUKEWARNING}。微软文档明确写着该标志
 * <b>部分覆盖 FOF_NOCONFIRMATION</b>：正常文件静默进回收站，
 * 而被“核弹式”永久删除的文件会弹出系统警告框。
 * 这样既没有多余弹窗，又堵住了静默永久删除的缺口。
 *
 * <h2>已知限制</h2>
 * {@code SHFileOperationW} 不支持超过 MAX_PATH（260 字符）的路径，也不支持 {@code \\?\} 前缀。
 * 遇到超长路径会返回错误，界面按“失败 → 询问是否永久删除（由 Java NIO 处理，支持长路径）”降级。
 */
public final class Shell32 {

    /** 删除操作。 */
    private static final int FO_DELETE = 0x0003;

    /** 允许撤销：送入回收站。 */
    private static final int FOF_ALLOWUNDO = 0x0040;
    /** 对所有对话框回答“全是”。 */
    private static final int FOF_NOCONFIRMATION = 0x0010;
    /** 不显示进度对话框。 */
    private static final int FOF_SILENT = 0x0004;
    /** 不显示错误对话框（错误由我们自己的界面呈现）。 */
    private static final int FOF_NOERRORUI = 0x0400;
    /** 不确认是否创建新目录。 */
    private static final int FOF_NOCONFIRMMKDIR = 0x0200;
    /** 永久删除时给出警告，可部分覆盖 FOF_NOCONFIRMATION。 */
    private static final int FOF_WANTNUKEWARNING = 0x4000;

    private static final String LIBRARY_NAME = "shell32";

    /** 是否成功加载了 native 库。加载失败时所有能力降级（见 {@link #available()}）。 */
    private static final boolean AVAILABLE;
    private static final Shell32Api API;

    static {
        Shell32Api api = null;
        boolean ok = false;
        try {
            // 不使用 W32APIOptions：它会给函数名自动追加 W/A 后缀，
            // 而这里已显式声明 SHFileOperationW，用默认映射可避免“SHFileOperationWW”这类歧义。
            api = Native.load(LIBRARY_NAME, Shell32Api.class);
            ok = true;
        } catch (Throwable t) {
            System.err.println("[Shell32] 无法加载 shell32，回收站功能将不可用：" + t.getMessage());
        }
        API = api;
        AVAILABLE = ok;
    }

    private Shell32() {
    }

    /** native 库是否可用。不可用时界面应禁用“删除到回收站”而不是崩溃。 */
    public static boolean available() {
        return AVAILABLE;
    }

    /** native 接口声明。 */
    interface Shell32Api extends Library {
        int SHFileOperationW(SHFILEOPSTRUCTW fileOp);

        int SHQueryRecycleBinW(String pszRootPath, SHQUERYRBINFO queryInfo);
    }

    /**
     * {@code SHQUERYRBINFO} 结构体。
     *
     * <p>用来查询回收站当前的条目数与占用。存在的意义是让"文件到底进没进回收站"
     * <b>可被断言</b>：只看"原路径已不存在"无法区分"进了回收站"和"被永久删除了"，
     * 而这两者对用户的意义天差地别。
     */
    @Structure.FieldOrder({"cbSize", "i64Size", "i64NumItems"})
    public static class SHQUERYRBINFO extends Structure {

        public int cbSize;
        public long i64Size;
        public long i64NumItems;

        public SHQUERYRBINFO() {
            super();
            // 调用前必须填好结构体大小，否则 Shell 拒绝执行
            cbSize = size();
        }
    }

    /**
     * 查询指定卷的回收站条目数。
     *
     * @param driveRoot 卷根目录，如 {@code C:\}；传 null 表示所有卷
     * @return 条目数；查询失败返回 -1
     */
    public static long recycleBinItemCount(String driveRoot) {
        if (!AVAILABLE) {
            return -1;
        }
        try {
            SHQUERYRBINFO info = new SHQUERYRBINFO();
            info.write();
            int code = API.SHQueryRecycleBinW(driveRoot, info);
            info.read();
            return code == 0 ? info.i64NumItems : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * {@code SHFILEOPSTRUCTW} 结构体。
     *
     * <p>{@code fFlags} 在 C 里是 WORD（2 字节），这里刻意声明为 {@code int}：
     * 后面的字段本来就要按 4 字节对齐，所以在偏移 32 写入 4 字节与写入 2 字节+2 字节填充，
     * 对后续字段位置与 Windows 读取到的标志位都完全相同。这样写可以避开 JNA 填充计算的坑。
     */
    @Structure.FieldOrder({"hwnd", "wFunc", "pFrom", "pTo", "fFlags",
            "fAnyOperationsAborted", "hNameMappings", "lpszProgressTitle"})
    public static class SHFILEOPSTRUCTW extends Structure {

        public Pointer hwnd;
        public int wFunc;
        public Pointer pFrom;
        public Pointer pTo;
        public int fFlags;
        /** C 的 BOOL；用 0 / 非 0 判断，不用 boolean 以免依赖 JNA 的布尔宽度约定。 */
        public int fAnyOperationsAborted;
        public Pointer hNameMappings;
        public Pointer lpszProgressTitle;

        public SHFILEOPSTRUCTW() {
            super();
        }

        SHFILEOPSTRUCTW(Pointer pointer) {
            super(pointer);
            read();
        }
    }

    /** 一次回收站操作的结果。 */
    public record Result(boolean success, boolean aborted, int code, String message) {

        public boolean failed() {
            return !success;
        }
    }

    /** 送入回收站时使用的标志位组合。 */
    static final int RECYCLE_FLAGS = FOF_ALLOWUNDO | FOF_NOCONFIRMATION | FOF_SILENT
            | FOF_NOERRORUI | FOF_NOCONFIRMMKDIR | FOF_WANTNUKEWARNING;

    /**
     * 把一批路径送入回收站。
     *
     * @param paths 待删除的路径（绝对路径）
     */
    public static Result moveToRecycleBin(List<Path> paths) {
        return delete(paths, RECYCLE_FLAGS);
    }

    /**
     * 按指定标志位执行 Shell 删除。
     *
     * <p>做成带参数的包内方法，是为了让诊断程序能够对比不同标志位的实际行为——
     * "文档说 FOF_ALLOWUNDO 就会进回收站"和"在这台机器上真的进了回收站"是两件事，
     * 而后者才是用户关心的。
     */
    static Result delete(List<Path> paths, int flags) {
        if (!AVAILABLE) {
            return new Result(false, false, -1, "无法加载 shell32，回收站功能不可用");
        }
        if (paths == null || paths.isEmpty()) {
            return new Result(true, false, 0, "");
        }

        Memory from = doubleNullTerminatedWide(paths);
        SHFILEOPSTRUCTW op = new SHFILEOPSTRUCTW();
        op.hwnd = null;
        op.wFunc = FO_DELETE;
        op.pFrom = from;
        op.pTo = null;
        op.fFlags = flags;
        op.write();

        int code;
        try {
            code = API.SHFileOperationW(op);
            op.read();
        } catch (Throwable t) {
            return new Result(false, false, -1, "调用 Shell 失败：" + t.getMessage());
        }

        // from 必须在 native 调用期间保持强引用，这里显式使用一次以杜绝被提前回收
        if (from.size() == 0) {
            return new Result(false, false, -1, "内部错误：路径缓冲区为空");
        }

        boolean aborted = op.fAnyOperationsAborted != 0;
        if (code == 0 && !aborted) {
            return new Result(true, false, 0, "");
        }
        return new Result(false, aborted, code, describeError(code, aborted));
    }

    /**
     * 构造双 NUL 结尾的 UTF-16 路径列表。
     *
     * <p>Shell 要求 {@code pFrom} 是“每个字符串各自以 NUL 结尾，整体再以一个 NUL 结尾”。
     * 少写最后那个额外的 NUL 是这类调用最常见的错误，会让 Shell 读到缓冲区外的数据。
     */
    static Memory doubleNullTerminatedWide(List<Path> paths) {
        StringBuilder sb = new StringBuilder();
        for (Path p : paths) {
            String s = p.toAbsolutePath().toString();
            // Shell 不接受尾部反斜杠（除根目录外），先规范化掉
            if (s.length() > 3 && (s.endsWith("\\") || s.endsWith("/"))) {
                s = s.substring(0, s.length() - 1);
            }
            sb.append(s).append('\0');
        }
        sb.append('\0');

        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_16LE);
        Memory memory = new Memory(bytes.length + 2L);
        memory.write(0, bytes, 0, bytes.length);
        memory.setShort(bytes.length, (short) 0);
        return memory;
    }

    /** 把 Shell 返回码翻译成用户能懂的原因，并给出下一步建议。 */
    static String describeError(int code, boolean aborted) {
        if (aborted) {
            return "操作被中止（可能在系统确认框里选择了取消）";
        }
        return switch (code) {
            case 0 -> "";
            case 2 -> "找不到文件或文件夹（可能已被移动或删除）";
            case 3 -> "找不到指定的路径";
            case 5 -> "拒绝访问（文件可能正在被其他程序使用）";
            case 32 -> "文件正在被其他程序使用，无法删除";
            case 33 -> "文件的一部分被其他程序锁定，无法删除";
            case 0x71 -> "源文件与目标文件相同";
            case 0x78 -> "无权访问源文件";
            case 0x79 -> "路径过深，超出 Shell 的处理能力";
            case 0x7C -> "路径无效（可能是超长路径或含非法字符）";
            case 0x7D -> "目标位于源文件夹中";
            case 0x80 -> "文件与文件夹重名冲突";
            case 0x81 -> "文件名过长";
            case 0x85 -> "文件过大，回收站无法容纳";
            case 0xB7 -> "Shell 操作失败";
            default -> "Shell 返回错误码 0x" + Integer.toHexString(code)
                    + "（" + code + "）；若为文件过大所致，可改用永久删除";
        };
    }
}
