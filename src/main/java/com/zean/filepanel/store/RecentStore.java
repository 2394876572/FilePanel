package com.zean.filepanel.store;

import com.fasterxml.jackson.core.type.TypeReference;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最近使用的文件。
 *
 * <h2>为什么是「按路径去重 + 计数」而不是「操作流水」</h2>
 * 用户想看的是"我最近碰过哪些文件"，而不是"我按过几次打开"。
 * 同一个文件打开十次应该在列表里占一行、排在最前，而不是把整个列表刷满同一个名字。
 * 因此按路径 upsert：更新操作类型与时间、累加次数。
 *
 * <h2>两条必须维护一致性的路径</h2>
 * <ol>
 *   <li><b>重命名</b>：文件改名后最近记录里的路径就失效了，必须迁移，
 *       否则用户刚从"最近"里打开过的文件，改个名就从列表里消失了。</li>
 *   <li><b>文件被外部删除</b>：记录会变成死链。加载时做一次清理，但只清理"确实不存在"的，
 *       不因为一次读取失败就删掉（网络盘临时不可达时会误删）。</li>
 * </ol>
 */
public final class RecentStore extends JsonFileStore {

    public static final String FILE_NAME = "recent.json";

    /** 条目上限。超过后丢弃最旧的，避免文件无限增长。 */
    public static final int MAX_ENTRIES = 500;

    /** 操作类型。 */
    public enum Op {
        OPEN("打开"),
        REVEAL("打开所在文件夹"),
        COPY_PATH("复制路径"),
        RENAME("重命名"),
        TAG("打标签"),
        FAVORITE("收藏");

        private final String label;

        Op(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 一条最近使用记录。可变 POJO，便于 Jackson 反序列化时缺字段用默认值补齐。 */
    public static final class Entry {

        /** 原始大小写的绝对路径（展示用；查表用 {@link PathKey} 归一化）。 */
        public String path;

        /** 文件名，便于在界面上不必反解路径。 */
        public String name;

        /** 最后一次操作类型。 */
        public Op op = Op.OPEN;

        /** 最后一次操作时间（epoch 毫秒），用于排序。 */
        public long timestamp;

        /**
         * 单调递增的次序号。
         *
         * <p>存在的理由：{@code System.currentTimeMillis()} 只有毫秒精度，
         * 同一毫秒内连续操作两个文件时时间戳完全相同，排序结果就退化成"看谁先被遍历到"，
         * 表现为"最近使用"的顺序偶尔莫名其妙。加上次序号后排序是确定的。
         */
        public long seq;

        /** 累计操作次数。 */
        public int count = 1;

        /** 是否目录。 */
        public boolean directory;

        /** 大小（字节），便于卡片上直接显示而不用再取文件属性。 */
        public long size;

        public Entry() {
        }

        public static Entry of(Path path, String name, Op op, boolean directory, long size, long seq) {
            Entry entry = new Entry();
            entry.path = path.toAbsolutePath().normalize().toString();
            entry.name = name;
            entry.op = op == null ? Op.OPEN : op;
            entry.timestamp = System.currentTimeMillis();
            entry.seq = seq;
            entry.count = 1;
            entry.directory = directory;
            entry.size = size;
            return entry;
        }

        public Path pathValue() {
            return Path.of(path);
        }
    }

    /** 内存索引：归一化路径 -> 条目。 */
    private final Map<String, Entry> index = new LinkedHashMap<>();

    /** 下一个次序号。加载时取已有最大值 +1，保证跨次启动也单调递增。 */
    private long nextSeq = 1;

    private boolean loaded;

    public RecentStore(Path dataDirectory) {
        super(dataDirectory, FILE_NAME);
    }

    /** 确保已从磁盘加载（惰性）。 */
    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        List<Entry> list = readOrNull(new TypeReference<List<Entry>>() {
        });
        if (list != null) {
            for (Entry e : list) {
                if (e != null && e.path != null) {
                    index.put(PathKey.of(Path.of(e.path)), e);
                    nextSeq = Math.max(nextSeq, e.seq + 1);
                }
            }
        }
    }

    /**
     * 记录一次操作。
     *
     * @param path 文件路径
     * @param op   操作类型
     */
    public void record(Path path, Op op, boolean directory, long size) {
        if (path == null) {
            return;
        }
        ensureLoaded();
        String key = PathKey.of(path);
        Entry existing = index.get(key);
        long seq = nextSeq++;
        if (existing == null) {
            Path fileName = path.getFileName();
            index.put(key, Entry.of(path, fileName == null ? path.toString() : fileName.toString(),
                    op, directory, size, seq));
        } else {
            existing.op = op == null ? existing.op : op;
            existing.timestamp = System.currentTimeMillis();
            existing.seq = seq;
            existing.count++;
            existing.directory = directory;
            existing.size = size;
            // 路径大小写可能变了（例如仅大小写重命名），以最新一次为准
            existing.path = path.toAbsolutePath().normalize().toString();
        }
        persist();
    }

    /**
     * 把一条记录从一个路径迁移到另一个路径（重命名后必须调用）。
     *
     * @return 是否找到了源记录
     */
    public boolean remapPath(Path oldPath, Path newPath) {
        if (oldPath == null || newPath == null) {
            return false;
        }
        ensureLoaded();
        String oldKey = PathKey.of(oldPath);
        Entry entry = index.remove(oldKey);
        if (entry == null) {
            return false;
        }
        entry.path = newPath.toAbsolutePath().normalize().toString();
        Path fileName = newPath.getFileName();
        if (fileName != null) {
            entry.name = fileName.toString();
        }
        entry.timestamp = System.currentTimeMillis();
        entry.seq = nextSeq++;
        index.put(PathKey.of(newPath), entry);
        persist();
        return true;
    }

    /** 移除一条记录（例如文件被删除后）。 */
    public boolean remove(Path path) {
        if (path == null) {
            return false;
        }
        ensureLoaded();
        boolean removed = index.remove(PathKey.of(path)) != null;
        if (removed) {
            persist();
        }
        return removed;
    }

    /**
     * 清理已不存在的文件对应的记录。
     *
     * <p>只在"路径确实不存在"时清理。读取属性失败（例如网络盘暂时不可达）不算数——
     * 那种情况下删掉记录才是真的把用户数据弄丢了。
     *
     * @return 清理掉的条数
     */
    public int pruneMissing() {
        ensureLoaded();
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, Entry> e : index.entrySet()) {
            try {
                if (!Files.exists(e.getValue().pathValue())) {
                    toRemove.add(e.getKey());
                }
            } catch (RuntimeException ignored) {
                // 路径非法或无法判断：保守起见保留
            }
        }
        toRemove.forEach(index::remove);
        if (!toRemove.isEmpty()) {
            persist();
        }
        return toRemove.size();
    }

    /** 按最后操作时间倒序返回全部记录；同一毫秒内按次序号倒序，保证顺序确定。 */
    public List<Entry> all() {
        ensureLoaded();
        List<Entry> list = new ArrayList<>(index.values());
        list.sort(RECENT_FIRST);
        return list;
    }

    /**
     * "最近在前"的比较器。
     *
     * <p>时间戳可能相同（毫秒精度），所以必须用次序号兜底：
     * 否则同一毫秒内的多次操作顺序会变成"看遍历顺序"，表现为列表顺序偶尔乱跳。
     */
    private static final java.util.Comparator<Entry> RECENT_FIRST =
            java.util.Comparator.comparingLong((Entry e) -> e.timestamp).reversed()
                    .thenComparing(java.util.Comparator.comparingLong((Entry e) -> e.seq).reversed());

    /** 最近 N 条。 */
    public List<Entry> recent(int limit) {
        List<Entry> all = all();
        return all.size() <= limit ? all : all.subList(0, limit);
    }

    /** 是否记录过该路径。 */
    public boolean contains(Path path) {
        ensureLoaded();
        return index.containsKey(PathKey.of(path));
    }

    /** 当前条数。 */
    public int size() {
        ensureLoaded();
        return index.size();
    }

    /** 清空全部记录。 */
    public void clear() {
        ensureLoaded();
        index.clear();
        persist();
    }

    private void persist() {
        List<Entry> list = new ArrayList<>(index.values());
        list.sort(RECENT_FIRST);
        if (list.size() > MAX_ENTRIES) {
            list = new ArrayList<>(list.subList(0, MAX_ENTRIES));
            index.clear();
            for (Entry e : list) {
                index.put(PathKey.of(Path.of(e.path)), e);
            }
        }
        write(list);
    }
}
