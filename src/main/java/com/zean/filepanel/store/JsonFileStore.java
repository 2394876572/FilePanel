package com.zean.filepanel.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 基于 JSON 文件的存储基类。
 *
 * <p>M4 一下子多出四个存储（最近使用、收藏、标签、索引缓存），加上原有的配置与删除日志，
 * 一共六个。它们的容错要求完全相同，抄六遍必然出现"某个存储忘了处理损坏文件"这类不一致。
 * 抽成基类后，下面这两条<b>硬性要求</b>只需要在一处保证：
 *
 * <ol>
 *   <li><b>读失败绝不能拦住启动</b>：文件不存在、JSON 损坏、没有读权限，一律返回默认值。
 *       用户宁可丢一点偏好，也不能因为一个数据文件坏了就打不开软件。</li>
 *   <li><b>写失败绝不能崩</b>：便携程序可能被放进只读目录或 U 盘。
 *       写不进去就记一条日志继续跑，功能照常（只是下次不记得）。</li>
 * </ol>
 *
 * <p>目录创建后会尝试设置隐藏属性：Windows 上 {@code .filepanel} 这种点开头的目录
 * 在资源管理器里<b>并不会自动隐藏</b>，用户看到自己的资料文件夹里多出一个莫名其妙的目录会不安。
 *
 * <p>线程约定：这些存储在界面上只在 JavaFX 线程被读写，因此不加同步。
 * 后台线程（扫描、统计）不触碰它们。
 */
public abstract class JsonFileStore {

    /**
     * 共享的 ObjectMapper。
     *
     * <p>配置完成后 ObjectMapper 是线程安全的，六个存储各建一个纯属浪费。
     * {@code FAIL_ON_UNKNOWN_PROPERTIES} 关闭是为了让旧版本文件在降级运行、
     * 或用户手工编辑过之后仍能读进来。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /**
     * 不缩进的 mapper，用于体积敏感的数据。
     *
     * <p>为什么需要两套：配置与收藏/标签是"人可能会去翻一眼"的东西，缩进让排查方便得多；
     * 而索引缓存是纯机器数据（几十万条、几百 KB 到几十 MB），缩进只会让它翻倍，
     * 既多占磁盘也拖慢解析，没有任何可读性收益。
     */
    private static final ObjectMapper COMPACT_MAPPER = new ObjectMapper()
            .disable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Path directory;
    private final Path file;

    protected JsonFileStore(Path dataDirectory, String fileName) {
        this.directory = dataDirectory;
        this.file = dataDirectory == null ? null : dataDirectory.resolve(fileName);
    }

    /** 数据目录（通常是 {@code <根目录>/.filepanel}）。 */
    public Path directory() {
        return directory;
    }

    /** 本存储对应的文件路径；可能尚不存在。 */
    public Path file() {
        return file;
    }

    /** 文件是否存在。 */
    public boolean exists() {
        return file != null && Files.isRegularFile(file);
    }

    /**
     * 读取并解析；文件不存在或解析失败时返回 null。
     *
     * <p>返回 null 而不是抛异常，是为了让调用方用一行代码表达"读不到就用默认值"。
     */
    protected <T> T readOrNull(TypeReference<T> type) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return MAPPER.readValue(file.toFile(), type);
        } catch (IOException | RuntimeException e) {
            System.err.println("[" + getClass().getSimpleName() + "] 读取失败，按空处理："
                    + e.getMessage());
            return null;
        }
    }

    /** 读取单个对象；同上。 */
    protected <T> T readOrNull(Class<T> type) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return MAPPER.readValue(file.toFile(), type);
        } catch (IOException | RuntimeException e) {
            System.err.println("[" + getClass().getSimpleName() + "] 读取失败，按默认值处理："
                    + e.getMessage());
            return null;
        }
    }

    /**
     * 写入。
     *
     * @return 是否成功；失败只记日志，调用方不应因此中断任何流程
     */
    protected boolean write(Object value) {
        return write(value, false);
    }

    /** 紧凑写入（不缩进），用于索引缓存这类体积敏感的数据。 */
    protected boolean writeCompact(Object value) {
        return write(value, true);
    }

    private boolean write(Object value, boolean compact) {
        if (file == null || value == null) {
            return false;
        }
        try {
            Files.createDirectories(directory);
            hideDirectory();
            (compact ? COMPACT_MAPPER : MAPPER).writeValue(file.toFile(), value);
            return true;
        } catch (IOException | RuntimeException e) {
            System.err.println("[" + getClass().getSimpleName() + "] 保存失败（不影响使用）："
                    + e.getMessage());
            return false;
        }
    }

    /** 删除数据文件；用于"清空"类操作。 */
    protected boolean deleteFile() {
        if (file == null) {
            return false;
        }
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            System.err.println("[" + getClass().getSimpleName() + "] 删除失败：" + e.getMessage());
            return false;
        }
    }

    /** Windows 上给数据目录加隐藏属性；其他平台或权限不足时静默跳过。 */
    private void hideDirectory() {
        if (directory == null) {
            return;
        }
        try {
            Files.setAttribute(directory, "dos:hidden", true);
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException ignored) {
            // 非 Windows 或不允许设属性：不是错误
        }
    }

    /** 供子类在需要时使用的根路径包装。 */
    protected static UncheckedIOException wrap(IOException e) {
        return new UncheckedIOException(e);
    }
}
