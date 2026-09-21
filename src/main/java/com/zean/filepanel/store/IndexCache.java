package com.zean.filepanel.store;

import com.zean.filepanel.core.FileItem;
import com.zean.filepanel.core.FileKind;
import com.zean.filepanel.core.ScanResult;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 扫描结果的内存快照缓存。
 *
 * <p>作用：二次启动时<b>先秒开上次的结果</b>，再在后台重新扫描并替换。
 * 用户感知到的差别很大——大目录下从"盯着加载页等几秒"变成"立刻看到内容"。
 *
 * <h2>为什么要有条目上限</h2>
 * 缓存是 JSON。10 万条目大约会产生几十 MB 的文本，写和读都比重新扫描还慢，
 * 而且解析时会一次性构造大量对象。超过 {@link #MAX_CACHED_ITEMS} 就直接不写缓存，
 * 让下次老老实实扫描——这比"缓存反而更慢"要好。
 *
 * <h2>为什么缓存里没有"被隐藏的目录"信息</h2>
 * 那部分只是用于状态栏统计，且必须重新统计才有意义（文件可能已经变了）。
 * 缓存只负责"尽快把列表画出来"，随后的后台扫描会把完整信息补齐。
 */
public final class IndexCache extends JsonFileStore {

    public static final String FILE_NAME = "index.json";

    /**
     * 超过此条目数不写缓存。见类注释。
     *
     * <p>数值来自实测：紧凑写入后约 <b>420 字节/条</b>（112 条 = 47 KB），
     * 因此 2 万条约 8 MB——解析并构造对象大约 0.1~0.2 秒，仍在"秒开"预算内。
     * 若放宽到 5 万条就是约 21 MB，加上随后填充表格与排序的开销，
     * 总耗时会逼近甚至超过 1 秒，"秒开"这个承诺就不再成立。
     * 与其给一个名不副实的缓存，不如超过这个规模就老老实实重新扫描。
     */
    public static final int MAX_CACHED_ITEMS = 20_000;

    /** 缓存文件的结构版本。字段含义变化时递增，旧缓存会被直接忽略。 */
    public static final int FORMAT_VERSION = 1;

    /** 顶层快照。 */
    public static final class Snapshot {
        public int version = FORMAT_VERSION;
        public String root;
        public long scannedAt;
        public long fileCount;
        public long dirCount;
        public long totalBytes;
        public long hiddenCount;
        public long elapsedMillis;
        public List<Item> items = new ArrayList<>();
    }

    /** 单个条目的紧凑形式。 */
    public static final class Item {
        public String path;
        public String rel;
        public String parent;
        public boolean dir;
        public String ext;
        public long size;
        /** epoch 毫秒；0 表示未知（对应 FileItem 里的 null）。 */
        public long created;
        public long modified;
        public long accessed;
        public boolean hidden;
        public boolean readOnly;
        public boolean system;
    }

    public IndexCache(Path dataDirectory) {
        super(dataDirectory, FILE_NAME);
    }

    /**
     * 保存扫描结果。
     *
     * @return 是否写入成功；条目过多或写失败都返回 false，调用方无需特殊处理
     */
    public boolean save(ScanResult result) {
        if (result == null || result.items().isEmpty()) {
            return false;
        }
        if (result.items().size() > MAX_CACHED_ITEMS) {
            // 明确放弃，而不是硬写一个又大又慢的文件
            deleteFile();
            return false;
        }
        Snapshot snapshot = new Snapshot();
        snapshot.root = result.root().toString();
        snapshot.scannedAt = System.currentTimeMillis();
        snapshot.fileCount = result.fileCount();
        snapshot.dirCount = result.dirCount();
        snapshot.totalBytes = result.totalBytes();
        snapshot.hiddenCount = result.hiddenCount();
        snapshot.elapsedMillis = result.elapsedMillis();
        snapshot.items = new ArrayList<>(result.items().size());
        for (FileItem item : result.items()) {
            snapshot.items.add(toItem(item));
        }
        // 用紧凑写入：索引是纯机器数据，缩进会让它体积翻倍、解析变慢，却没人会去读它
        return writeCompact(snapshot);
    }

    /**
     * 读取缓存。
     *
     * <p>只在"缓存记录的根目录与当前根目录一致"且版本匹配时才返回，
     * 否则宁可让界面重新扫描，也不能把别的文件夹的内容显示出来。
     */
    public Optional<ScanResult> load(Path root) {
        Snapshot snapshot = readOrNull(Snapshot.class);
        if (snapshot == null || snapshot.items == null || snapshot.items.isEmpty()) {
            return Optional.empty();
        }
        if (snapshot.version != FORMAT_VERSION) {
            return Optional.empty();
        }
        if (root == null || snapshot.root == null
                || !PathKey.same(Path.of(snapshot.root), root)) {
            return Optional.empty();
        }

        List<FileItem> items = new ArrayList<>(snapshot.items.size());
        for (Item item : snapshot.items) {
            FileItem restored = toFileItem(item);
            if (restored != null) {
                items.add(restored);
            }
        }
        if (items.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new ScanResult(
                root,
                items,
                // 缓存不含"被剪枝目录"清单：它只用于隐藏内容统计，交给随后的真实扫描补齐
                List.of(),
                0L, 0L,
                // 同理，缓存不含读取失败清单
                List.of(), 0L,
                snapshot.fileCount, snapshot.dirCount, snapshot.totalBytes,
                snapshot.hiddenCount,
                snapshot.elapsedMillis,
                false, false));
    }

    /** 缓存时间（epoch 毫秒）；无缓存返回 0。 */
    public long cachedAt() {
        Snapshot snapshot = readOrNull(Snapshot.class);
        return snapshot == null ? 0L : snapshot.scannedAt;
    }

    /** 删除缓存（根目录变更时应调用，避免把 A 文件夹的内容当成 B 的缓存）。 */
    public boolean invalidate() {
        return deleteFile();
    }

    private static Item toItem(FileItem item) {
        Item out = new Item();
        out.path = item.path().toString();
        out.rel = item.relPath();
        out.parent = item.parentRel();
        out.dir = item.directory();
        out.ext = item.ext();
        out.size = item.size();
        out.created = millis(item.created());
        out.modified = millis(item.modified());
        out.accessed = millis(item.accessed());
        out.hidden = item.hidden();
        out.readOnly = item.readOnly();
        out.system = item.system();
        return out;
    }

    private static FileItem toFileItem(Item item) {
        if (item == null || item.path == null) {
            return null;
        }
        try {
            Path path = Path.of(item.path);
            String name = path.getFileName() == null ? item.path : path.getFileName().toString();
            String ext = item.dir ? "" : (item.ext == null ? FileItem.extensionOf(name) : item.ext);
            String rel = item.rel == null ? name : item.rel;
            int depth = Math.max(0, rel.split("/").length - 1);
            return new FileItem(
                    name, path, rel, item.parent == null ? "" : item.parent,
                    item.dir, ext,
                    item.dir ? FileKind.DIRECTORY : FileKind.ofExtension(ext),
                    item.size,
                    fileTime(item.created), fileTime(item.modified), fileTime(item.accessed),
                    depth, item.hidden, item.readOnly, item.system);
        } catch (RuntimeException e) {
            // 单条缓存损坏不应该让整份缓存作废
            return null;
        }
    }

    private static long millis(FileTime time) {
        return time == null ? 0L : time.toMillis();
    }

    private static FileTime fileTime(long millis) {
        return millis <= 0 ? null : FileTime.fromMillis(millis);
    }
}
