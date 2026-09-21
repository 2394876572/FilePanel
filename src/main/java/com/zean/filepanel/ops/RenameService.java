package com.zean.filepanel.ops;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 重命名服务。
 *
 * <p>拆成「校验」与「执行」两步，是因为校验逻辑要能在界面上<b>边打字边跑</b>：
 * 用户每敲一个字符就要知道这个名字行不行，而那一刻不应该去碰磁盘、更不应该抛异常。
 *
 * <h2>Windows 上真正会出问题的地方</h2>
 * 这些事情在 Linux 上无所谓，在 Windows 上却会静默出错或直接失败：
 * <ul>
 *   <li>保留设备名：{@code CON}、{@code PRN}、{@code NUL}、{@code COM1}… 连 {@code CON.txt} 也不行</li>
 *   <li>结尾的点和空格会被系统<b>静默丢弃</b>：输入 {@code 报告.} 实际得到 {@code 报告}</li>
 *   <li>非法字符 {@code < > : " / \ | ? *}</li>
 *   <li>仅大小写不同的重命名：目标“已存在”，直接 move 会失败，必须两步走</li>
 *   <li>重名判断要忽略大小写，否则 {@code a.txt} 与 {@code A.txt} 会被当成两个不同名字</li>
 * </ul>
 */
public final class RenameService {

    /** Windows 文件名非法字符。 */
    private static final Pattern ILLEGAL_CHARS = Pattern.compile("[<>:\"/\\\\|?*\\x00-\\x1F]");

    /** Windows 保留设备名（不含扩展名比较，{@code CON.txt} 同样非法）。 */
    private static final Set<String> RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    /** 文件名最大长度（NTFS 是 255 个 UTF-16 码元）。 */
    private static final int MAX_NAME_LENGTH = 255;

    private RenameService() {
    }

    /**
     * 校验结果。
     *
     * <p>工厂方法刻意<b>不叫</b> {@code ok}：record 的组件已经生成了 {@code ok()} 访问器，
     * 同名的静态工厂会因为返回类型不同（{@code Validation} vs {@code boolean}）而遮蔽访问器，
     * 导致 {@code !result.ok()} 这类写法直接编译不过。
     */
    public record Validation(boolean ok, String message) {

        public static Validation valid() {
            return new Validation(true, "");
        }

        public static Validation invalid(String message) {
            return new Validation(false, message);
        }
    }

    /** 执行结果。 */
    public record Result(boolean success, Path newPath, boolean caseOnlyChange, String error) {

        public static Result failure(String error) {
            return new Result(false, null, false, error);
        }
    }

    /**
     * 纯语法校验，不访问文件系统。
     *
     * @param name 用户输入的新文件名（含扩展名；目录则为整个名字）
     */
    public static Validation validateName(String name) {
        if (name == null || name.isEmpty()) {
            return Validation.invalid("文件名不能为空");
        }
        if (name.isBlank()) {
            return Validation.invalid("文件名不能只有空格");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            return Validation.invalid("文件名过长（最多 " + MAX_NAME_LENGTH + " 个字符，当前 "
                    + name.length() + "）");
        }
        if (".".equals(name) || "..".equals(name)) {
            return Validation.invalid("「" + name + "」是保留的目录名");
        }
        if (ILLEGAL_CHARS.matcher(name).find()) {
            return Validation.invalid("不能包含这些字符：< > : \" / \\ | ? *");
        }
        if (name.endsWith(".")) {
            return Validation.invalid("文件名不能以点结尾（Windows 会静默丢弃末尾的点）");
        }
        if (name.endsWith(" ")) {
            return Validation.invalid("文件名不能以空格结尾（Windows 会静默丢弃末尾的空格）");
        }
        String base = baseName(name);
        if (RESERVED_NAMES.contains(base.toUpperCase(Locale.ROOT))) {
            return Validation.invalid("「" + base + "」是 Windows 保留设备名，不能用作文档名");
        }
        return Validation.valid();
    }

    /** 去掉扩展名后的部分；无扩展名时返回整个名字。 */
    static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    /**
     * 目标是否可用（冲突检查）。
     *
     * @param source 原路径
     * @param target 目标路径
     */
    public static Validation validateTarget(Path source, Path target) {
        if (source == null || target == null) {
            return Validation.invalid("路径无效");
        }
        if (source.equals(target)) {
            // 名字完全没变：不是错误，但也没必要执行
            return Validation.valid();
        }
        if (Files.exists(target)) {
            // 仅大小写变化时，Windows 认为目标“已存在”其实指向同一个文件，这是合法的重命名
            boolean caseOnly = source.getFileName().toString()
                    .equalsIgnoreCase(target.getFileName().toString());
            if (caseOnly) {
                return Validation.valid();
            }
            return Validation.invalid("已存在同名文件或文件夹：「" + target.getFileName() + "」");
        }
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return Validation.invalid("目标目录不存在");
        }
        return Validation.valid();
    }

    /** 是否只是改变了大小写。 */
    public static boolean isCaseOnlyChange(Path source, String newName) {
        String oldName = source.getFileName().toString();
        return oldName.equalsIgnoreCase(newName) && !oldName.equals(newName);
    }

    /**
     * 执行重命名。
     *
     * <p>两种特殊处理：
     * <ul>
     *   <li><b>仅大小写变化</b>：先用一个临时名过渡，再改成目标名。
     *       直接 move 会因"目标已存在"而失败。</li>
     *   <li><b>绝不覆盖</b>：move 不带 REPLACE_EXISTING。宁可失败，也不能悄悄覆盖掉另一个文件。</li>
     * </ul>
     */
    public static Result rename(Path source, String newName) {
        if (source == null) {
            return Result.failure("路径无效");
        }
        Validation nameCheck = validateName(newName);
        if (!nameCheck.ok()) {
            return Result.failure(nameCheck.message());
        }

        Path target;
        try {
            target = source.resolveSibling(newName);
        } catch (InvalidPathException e) {
            return Result.failure("文件名含非法字符：" + e.getMessage());
        }

        Validation targetCheck = validateTarget(source, target);
        if (!targetCheck.ok()) {
            return Result.failure(targetCheck.message());
        }

        boolean caseOnly = isCaseOnlyChange(source, newName);
        // 注意：Windows 上的 Path.equals 是<b>忽略大小写</b>的，所以 readme.md 与 README.md
        // 会被判为“相同路径”。早期版本在这里直接返回“成功且未变化”，结果是文件纹丝不动、
        // 界面却报告改成功了——一个完全静默失效的功能。
        // 必须先算出 caseOnly，再决定要不要短路。
        if (source.equals(target) && !caseOnly) {
            return new Result(true, source, false, "");
        }

        try {
            if (caseOnly) {
                Path temporary = source.resolveSibling(newName + ".filepanel-rename-" + System.nanoTime());
                Files.move(source, temporary);
                try {
                    Files.move(temporary, target);
                } catch (IOException e) {
                    // 回滚，避免把文件留在一个奇怪的名字上
                    try {
                        Files.move(temporary, source);
                    } catch (IOException rollbackFailure) {
                        return Result.failure("重命名失败且回滚未成功，文件当前名为「"
                                + temporary.getFileName() + "」：" + e.getMessage());
                    }
                    return Result.failure("重命名失败：" + e.getMessage());
                }
                return new Result(true, target, true, "");
            }
            // 刻意不用 StandardCopyOption.ATOMIC_MOVE：它的规范允许在目标已存在时
            // "由实现决定"是否替换，而 Windows 上实测就是直接替换。
            // 上面虽然做过冲突检查，但从检查到移动之间有时间窗，
            // 一旦有人在这期间创建了同名文件，ATOMIC_MOVE 会把它悄悄删掉。
            // 不带选项的 move 在目标存在时抛 FileAlreadyExistsException——宁可失败，不可覆盖。
            Files.move(source, target);
            return new Result(true, target, false, "");
        } catch (FileAlreadyExistsException e) {
            return Result.failure("已存在同名文件或文件夹");
        } catch (IOException e) {
            return Result.failure("重命名失败：" + e.getMessage());
        }
    }

    /** 供界面提示：把一个文件名拆成「主名 + 扩展名」，用于扩展名保护。 */
    public record NameParts(String base, String extension) {

        /** 是否可分离（无扩展名或点开头时为 false，此时整个名字都可编辑）。 */
        public boolean splittable() {
            return !extension.isEmpty();
        }

        public String join(String newBase) {
            return newBase + extension;
        }
    }

    /** 拆分文件名；点开头的文件（如 {@code .gitignore}）视为无扩展名。 */
    public static NameParts split(String fileName) {
        if (fileName == null) {
            return new NameParts("", "");
        }
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return new NameParts(fileName, "");
        }
        return new NameParts(fileName.substring(0, dot), fileName.substring(dot));
    }
}
