package com.zean.filepanel.core;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 文件夹扫描器。
 *
 * <p>设计要点：
 * <ol>
 *   <li><b>目录级剪枝</b>：命中排除规则的目录直接 {@code SKIP_SUBTREE}，省下整棵子树的遍历，
 *       这是本机 1245 → 约 170 个文件这个数量级差异的来源。</li>
 *   <li><b>错误不中断</b>：单个目录权限不足只记录并跳过，其余照常产出。</li>
 *   <li><b>进度节流</b>：最多每 100ms 或每 250 个条目回调一次，避免高频跨线程事件压垮 UI 线程。</li>
 *   <li><b>零额外系统调用取隐藏属性</b>：Windows 上 {@code walkFileTree} 传入的属性对象本就实现了
 *       {@link DosFileAttributes}，直接向下转型即可；其他平台才回退到 {@code Files.isHidden}。</li>
 *   <li><b>双重上限</b>：条目数上限防止内存失控，深度上限防止目录联接（junction）成环导致无限递归。</li>
 * </ol>
 *
 * <p><b>不使用 FOLLOW_LINKS</b>：跟随符号链接会引入环路风险，且用户通常不希望扫描穿透到别处。
 */
public final class Scanner {

    /** 收录条目上限。超过即截断并在结果中标记，防止异常目录树撑爆内存。 */
    public static final int MAX_ITEMS = 200_000;

    /** 记录到结果里的问题上限（总数仍完整统计，避免问题目录过多时列表无限膨胀）。 */
    public static final int MAX_ERRORS_RECORDED = 100;

    /**
     * 最大扫描深度（相对根目录）。
     *
     * <p>存在的理由：Windows 的目录联接（junction）在 NIO 里通常表现为普通目录，
     * 若成环会导致无限递归。目标目录实测最深 12 层，留 5 倍余量足够。
     */
    public static final int MAX_DEPTH = 64;

    /** 隐藏内容统计的条目上限，超过则结果标记为“下界”。 */
    public static final long MAX_HIDDEN_MEASURE_FILES = 200_000;

    private static final long PROGRESS_MIN_INTERVAL_MS = 100;
    private static final long PROGRESS_MIN_INTERVAL_ITEMS = 250;

    /** 进度回调。实现方需自行切回 UI 线程。 */
    @FunctionalInterface
    public interface Listener {
        void onProgress(ScanProgress progress);
    }

    private final Exclusions exclusions;
    private final Listener listener;

    /**
     * @param exclusions 排除规则；传 null 等价于不排除任何内容
     * @param listener   进度回调，允许为 null
     */
    public Scanner(Exclusions exclusions, Listener listener) {
        this.exclusions = exclusions == null ? Exclusions.none() : exclusions;
        this.listener = listener;
    }

    /**
     * 执行一次扫描。
     *
     * <p>本方法<b>不在内部吞掉一切</b>之外还保证：无论发生什么，调用方都能拿到一个 {@link ScanResult}，
     * 而不是异常。UI 必须能根据结果渲染出界面。
     *
     * @param root      扫描根目录（会被转为绝对规范路径）
     * @param cancelled 取消判定；返回 true 时尽快停止并把已扫到的部分作为结果返回
     */
    public ScanResult scan(Path root, BooleanSupplier cancelled) {
        if (root == null) {
            throw new IllegalArgumentException("扫描根目录不能为 null");
        }
        Path absRoot = root.toAbsolutePath().normalize();
        long startedAt = System.nanoTime();

        Walk walk = new Walk(absRoot, cancelled);
        try {
            Files.walkFileTree(absRoot, walk);
        } catch (IOException | RuntimeException e) {
            // 起始路径不可访问等极端情况：记录后照常返回结果
            walk.recordError(absRoot, e);
        }

        long elapsed = (System.nanoTime() - startedAt) / 1_000_000L;
        emitFinalProgress(walk);

        return new ScanResult(
                absRoot,
                List.copyOf(walk.items),
                List.copyOf(walk.excludedDirs),
                walk.excludedFiles,
                walk.excludedFileBytes,
                List.copyOf(walk.errors),
                walk.errorCount,
                walk.files,
                walk.dirs,
                walk.bytes,
                walk.hiddenCount,
                elapsed,
                walk.cancelledFlag,
                walk.truncated);
    }

    private void emitFinalProgress(Walk walk) {
        if (listener == null) {
            return;
        }
        listener.onProgress(new ScanProgress(
                walk.files, walk.dirs, walk.bytes,
                walk.excludedDirs.size() + walk.excludedFiles,
                walk.currentPath));
    }

    /** 一次扫描的可变状态。做成内部类以免用数组在方法间传递可变计数器。 */
    private final class Walk implements java.nio.file.FileVisitor<Path> {

        private final Path root;
        private final BooleanSupplier cancelled;
        private final List<FileItem> items = new ArrayList<>();
        private final List<Path> excludedDirs = new ArrayList<>();
        private final List<ScanError> errors = new ArrayList<>();

        private long files;
        private long dirs;
        private long bytes;
        private long excludedFiles;
        private long excludedFileBytes;
        private long errorCount;
        private long hiddenCount;
        private boolean cancelledFlag;
        private boolean truncated;
        private String currentPath = "";

        private long lastProgressAt = System.currentTimeMillis();
        private long lastProgressItems;

        private Walk(Path root, BooleanSupplier cancelled) {
            this.root = root;
            this.cancelled = cancelled;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            if (stopRequested()) {
                return FileVisitResult.TERMINATE;
            }
            if (dir.equals(root)) {
                currentPath = dir.toString();
                return FileVisitResult.CONTINUE;
            }
            if (items.size() >= MAX_ITEMS) {
                truncated = true;
                return FileVisitResult.TERMINATE;
            }

            int depth = dir.getNameCount() - root.getNameCount();
            if (depth >= MAX_DEPTH) {
                truncated = true;
                recordError(dir, new IOException(
                        "目录层级超过 " + MAX_DEPTH + " 层，已跳过（可能是目录联接形成的环）"));
                return FileVisitResult.SKIP_SUBTREE;
            }

            if (exclusions.excludesDirectory(dir)) {
                excludedDirs.add(dir);
                report(dir);
                return FileVisitResult.SKIP_SUBTREE;
            }

            dirs++;
            if (isHidden(attrs, dir)) {
                hiddenCount++;
            }
            items.add(toItem(dir, attrs, true));
            report(dir);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (stopRequested()) {
                return FileVisitResult.TERMINATE;
            }
            if (items.size() >= MAX_ITEMS) {
                truncated = true;
                return FileVisitResult.TERMINATE;
            }

            if (exclusions.excludesFile(file)) {
                excludedFiles++;
                excludedFileBytes += attrs.size();
                report(file);
                return FileVisitResult.CONTINUE;
            }

            files++;
            bytes += attrs.size();
            if (isHidden(attrs, file)) {
                hiddenCount++;
            }
            items.add(toItem(file, attrs, false));
            report(file);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            if (stopRequested()) {
                return FileVisitResult.TERMINATE;
            }
            // 关键：权限不足等问题只记录，绝不让整次扫描失败
            recordError(file, exc);
            report(file);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
            if (exc != null) {
                recordError(dir, exc);
            }
            return FileVisitResult.CONTINUE;
        }

        private boolean stopRequested() {
            if (cancelledFlag) {
                return true;
            }
            if (cancelled != null && cancelled.getAsBoolean()) {
                cancelledFlag = true;
                return true;
            }
            return false;
        }

        private void recordError(Path path, Throwable t) {
            errorCount++;
            if (errors.size() < MAX_ERRORS_RECORDED) {
                errors.add(ScanError.of(path, t));
            }
        }

        /** 按时间或条目数节流后回调进度。 */
        private void report(Path path) {
            currentPath = path.toString();
            if (listener == null) {
                return;
            }
            long now = System.currentTimeMillis();
            long seen = files + dirs + excludedDirs.size() + excludedFiles;
            if (now - lastProgressAt < PROGRESS_MIN_INTERVAL_MS
                    && seen - lastProgressItems < PROGRESS_MIN_INTERVAL_ITEMS) {
                return;
            }
            lastProgressAt = now;
            lastProgressItems = seen;
            listener.onProgress(new ScanProgress(files, dirs, bytes, seen - files - dirs, currentPath));
        }

        private FileItem toItem(Path path, BasicFileAttributes attrs, boolean directory) {
            Path rel = root.relativize(path);
            Path parent = rel.getParent();
            Path fileName = path.getFileName();
            String name = fileName == null ? path.toString() : fileName.toString();
            boolean isDir = directory || attrs.isDirectory();
            String ext = isDir ? "" : FileItem.extensionOf(name);
            return new FileItem(
                    name,
                    path,
                    toSlash(rel),
                    parent == null ? "" : toSlash(parent),
                    isDir,
                    ext,
                    isDir ? FileKind.DIRECTORY : FileKind.ofExtension(ext),
                    isDir ? 0L : attrs.size(),
                    attrs.creationTime(),
                    attrs.lastModifiedTime(),
                    attrs.lastAccessTime(),
                    Math.max(0, rel.getNameCount() - 1),
                    isHidden(attrs, path),
                    isReadOnly(attrs, path),
                    isSystem(attrs));
        }

        private static String toSlash(Path p) {
            return p.toString().replace('\\', '/');
        }

        private static boolean isHidden(BasicFileAttributes attrs, Path path) {
            if (attrs instanceof DosFileAttributes dos) {
                return dos.isHidden();
            }
            try {
                return Files.isHidden(path);
            } catch (IOException e) {
                return false;
            }
        }

        private static boolean isReadOnly(BasicFileAttributes attrs, Path path) {
            if (attrs instanceof DosFileAttributes dos) {
                return dos.isReadOnly();
            }
            return !Files.isWritable(path);
        }

        private static boolean isSystem(BasicFileAttributes attrs) {
            return attrs instanceof DosFileAttributes dos && dos.isSystem();
        }
    }

    /**
     * 统计被剪枝目录内部的规模。
     *
     * <p>返回的是被排除目录<b>内部</b>的文件与子目录（不含被排除目录本身——它们已计入
     * {@link ScanResult#excludedDirs()}）。合并口径请用 {@link #totalHidden}。
     *
     * <p>纯属性遍历，不构造对象、不排序，因此代价远低于主扫描。
     */
    public static HiddenStats measureHidden(List<Path> excludedDirs,
                                            BooleanSupplier cancelled,
                                            long maxFiles) {
        if (excludedDirs == null || excludedDirs.isEmpty()) {
            return HiddenStats.empty();
        }
        long[] counts = new long[3]; // 0=文件数 1=子目录数 2=字节数
        boolean[] truncated = new boolean[1];

        for (Path dir : excludedDirs) {
            if (truncated[0] || isCancelled(cancelled)) {
                break;
            }
            try {
                Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                        if (isCancelled(cancelled) || counts[0] >= maxFiles) {
                            truncated[0] = counts[0] >= maxFiles;
                            return FileVisitResult.TERMINATE;
                        }
                        if (!d.equals(dir)) {
                            counts[1]++;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
                        if (counts[0] >= maxFiles) {
                            truncated[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        counts[0]++;
                        counts[2] += attrs.size();
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path f, IOException e) {
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path d, IOException e) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException | RuntimeException ignored) {
                // 统计是附加信息，失败不应影响主流程
            }
        }
        return new HiddenStats(counts[0], counts[1], counts[2], truncated[0]);
    }

    private static boolean isCancelled(BooleanSupplier cancelled) {
        return cancelled != null && cancelled.getAsBoolean();
    }

    /**
     * 合并“扫描期直接排除的部分”与“被剪枝目录内部的部分”，得到完整的隐藏规模。
     *
     * <p>集中在这里做，是为了保证 CLI 与 GUI 两条路径的统计口径绝对一致——
     * 否则很容易出现界面显示一套数字、日志显示另一套的情况。
     */
    public static HiddenStats totalHidden(ScanResult result, HiddenStats measured) {
        return new HiddenStats(
                result.excludedFileCount() + measured.fileCount(),
                result.excludedDirs().size() + measured.dirCount(),
                result.excludedFileBytes() + measured.bytes(),
                measured.truncated());
    }
}
