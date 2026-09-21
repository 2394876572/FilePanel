package com.zean.filepanel.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.zean.filepanel.core.FileItem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件内容的全文索引。
 *
 * <h2>失效判定靠"大小 + 修改时间"，不是靠路径</h2>
 * 路径相同但内容被改过的文件必须重新抽取。用 {@code size + lastModifiedTime} 做指纹是
 * 廉价且够用的做法：文件被编辑后这两者几乎必然变化。只用路径会一直拿着旧文本搜索，
 * 用户会搜到已经删掉的段落。
 *
 * <h2>为什么要设总量上限</h2>
 * 抽取出来的文本是纯内存 + 一个 JSON 文件。不设上限的话，一个装满大文档的目录能轻松吃掉
 * 几百 MB 内存与同样量级的磁盘。到上限就停止收录新文件（而不是淘汰旧的）——
 * 顺序是先索引到的先保留，行为可预期。
 */
public final class ContentIndex extends JsonFileStore {

    public static final String FILE_NAME = "content-index.json";

    /** 索引里保留的总字符数上限。约等于 8 MB 内存（UTF-16）。 */
    public static final long MAX_TOTAL_CHARS = 4_000_000L;

    /** 单条索引占的字符数上限（与抽取层的上限一致，这里再兜一次）。 */
    public static final int MAX_CHARS_PER_ENTRY = 200_000;

    /** 一条索引记录。 */
    public static final class Entry {
        public String path;
        public long size;
        public long modified;
        public int chars;
        public boolean truncated;
        public String text;

        public Entry() {
        }
    }

    /** 归一化路径 -> 记录。 */
    private final Map<String, Entry> index = new LinkedHashMap<>();
    private long totalChars;
    private boolean loaded;

    public ContentIndex(Path dataDirectory) {
        super(dataDirectory, FILE_NAME);
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        List<Entry> list = readOrNull(new TypeReference<List<Entry>>() {
        });
        if (list == null) {
            return;
        }
        for (Entry e : list) {
            if (e == null || e.path == null || e.text == null) {
                continue;
            }
            String key = PathKey.of(Path.of(e.path));
            index.put(key, e);
            totalChars += e.text.length();
        }
    }

    /**
     * 取某个条目的内容文本。
     *
     * @return 未索引、或指纹已变化时返回 null（调用方据此当作"没命中内容"）
     */
    public String textOf(FileItem item) {
        if (item == null) {
            return null;
        }
        return textOf(item.path(), item.size(), modifiedOf(item));
    }

    public String textOf(Path path, long size, long modified) {
        ensureLoaded();
        Entry entry = index.get(PathKey.of(path));
        if (entry == null) {
            return null;
        }
        // 指纹不一致说明文件变了，旧文本不能再用
        if (entry.size != size || entry.modified != modified) {
            return null;
        }
        return entry.text;
    }

    /** 是否已有有效索引（指纹一致）。 */
    public boolean isIndexed(FileItem item) {
        return textOf(item) != null;
    }

    /** 收录一条。超出总量上限时返回 false。 */
    public boolean put(Path path, long size, long modified, String text, boolean truncated) {
        if (path == null || text == null || text.isEmpty()) {
            return false;
        }
        ensureLoaded();
        String key = PathKey.of(path);
        Entry existing = index.get(key);
        if (existing != null) {
            totalChars -= existing.text.length();
            index.remove(key);
        }
        String stored = text.length() > MAX_CHARS_PER_ENTRY
                ? text.substring(0, MAX_CHARS_PER_ENTRY) : text;
        if (totalChars + stored.length() > MAX_TOTAL_CHARS) {
            // 到上限就停止收录，不淘汰已索引的：顺序可预期，用户也能理解
            return false;
        }
        Entry entry = new Entry();
        entry.path = path.toAbsolutePath().normalize().toString();
        entry.size = size;
        entry.modified = modified;
        entry.chars = stored.length();
        entry.truncated = truncated || stored.length() < text.length();
        entry.text = stored;
        index.put(key, entry);
        totalChars += stored.length();
        return true;
    }

    public boolean remove(Path path) {
        ensureLoaded();
        String key = PathKey.of(path);
        Entry removed = index.remove(key);
        if (removed == null) {
            return false;
        }
        totalChars -= removed.text.length();
        return true;
    }

    /** 重命名后迁移索引。 */
    public boolean remapPath(Path oldPath, Path newPath) {
        if (oldPath == null || newPath == null) {
            return false;
        }
        ensureLoaded();
        Entry entry = index.remove(PathKey.of(oldPath));
        if (entry == null) {
            return false;
        }
        entry.path = newPath.toAbsolutePath().normalize().toString();
        index.put(PathKey.of(newPath), entry);
        return true;
    }

    /** 清掉指向已不存在文件的索引。 */
    public int pruneMissing() {
        ensureLoaded();
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, Entry> e : index.entrySet()) {
            try {
                if (!Files.exists(Path.of(e.getValue().path))) {
                    toRemove.add(e.getKey());
                }
            } catch (RuntimeException ignored) {
                // 路径非法：保守保留
            }
        }
        for (String key : toRemove) {
            Entry removed = index.remove(key);
            if (removed != null) {
                totalChars -= removed.text.length();
            }
        }
        return toRemove.size();
    }

    /** 清空并删除磁盘上的索引。 */
    public void clear() {
        ensureLoaded();
        index.clear();
        totalChars = 0;
        deleteFile();
    }

    /** 落盘。索引体积可能不小，用紧凑写入。 */
    public boolean save() {
        ensureLoaded();
        if (index.isEmpty()) {
            return false;
        }
        return writeCompact(new ArrayList<>(index.values()));
    }

    public int size() {
        ensureLoaded();
        return index.size();
    }

    public long totalChars() {
        ensureLoaded();
        return totalChars;
    }

    /** 是否已达总量上限。 */
    public boolean isFull() {
        ensureLoaded();
        return totalChars >= MAX_TOTAL_CHARS;
    }

    private static long modifiedOf(FileItem item) {
        return item.modified() == null ? 0L : item.modified().toMillis();
    }
}
