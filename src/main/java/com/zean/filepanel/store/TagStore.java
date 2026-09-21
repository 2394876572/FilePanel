package com.zean.filepanel.store;

import com.fasterxml.jackson.core.type.TypeReference;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 文件标签。
 *
 * <p>一个文件可以有多个标签；标签本身没有独立的生命周期，随最后一个使用它的文件消失而"自然消失"
 * （见 {@link #allTags()}：它是从现有映射推导出来的，不单独维护一张标签表）。
 * 这样就不会出现"删了所有文件，侧栏里还挂着一堆空标签"的情况。
 *
 * <p>标签字符串两端空白会被去掉、内部空白保留；空标签被拒绝。
 * 标签比较<b>不</b>忽略大小写——中文用户常混用「重要」和「重要 」，但英文场景下
 * {@code TODO} 与 {@code todo} 更像是有意区分。这个取舍写在这里以免后续被"顺手统一"。
 */
public final class TagStore extends JsonFileStore {

    public static final String FILE_NAME = "tags.json";

    /** 单个标签的长度上限，防止有人粘贴一整段文本当标签。 */
    public static final int MAX_TAG_LENGTH = 32;

    /** 每个文件的标签数上限。 */
    public static final int MAX_TAGS_PER_FILE = 20;

    /** 一个文件的标签记录。 */
    public static final class Entry {
        public String path;
        public List<String> tags = new ArrayList<>();

        public Entry() {
        }

        public static Entry of(Path path, List<String> tags) {
            Entry entry = new Entry();
            entry.path = path.toAbsolutePath().normalize().toString();
            entry.tags = new ArrayList<>(tags);
            return entry;
        }
    }

    /** 归一化路径 -> 标签列表。 */
    private final Map<String, List<String>> index = new LinkedHashMap<>();

    /** 归一化路径 -> 原始路径（保留大小写，写回文件时用）。 */
    private final Map<String, String> originalPaths = new LinkedHashMap<>();

    private boolean loaded;

    public TagStore(Path dataDirectory) {
        super(dataDirectory, FILE_NAME);
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        List<Entry> list = readOrNull(new TypeReference<List<Entry>>() {
        });
        if (list != null) {
            for (Entry e : list) {
                if (e == null || e.path == null || e.tags == null || e.tags.isEmpty()) {
                    continue;
                }
                String key = PathKey.of(Path.of(e.path));
                index.put(key, new ArrayList<>(e.tags));
                originalPaths.put(key, e.path);
            }
        }
    }

    /** 某个文件的标签（可能为空列表，不会为 null）。 */
    public List<String> tagsOf(Path path) {
        ensureLoaded();
        List<String> tags = index.get(PathKey.of(path));
        return tags == null ? List.of() : List.copyOf(tags);
    }

    /** 是否含有某个标签。 */
    public boolean hasTag(Path path, String tag) {
        String normalized = normalize(tag);
        return normalized != null && tagsOf(path).contains(normalized);
    }

    /**
     * 整体设置标签。
     *
     * @return 实际生效的标签列表（已去重、去空、限长）
     */
    public List<String> setTags(Path path, List<String> tags) {
        if (path == null) {
            return List.of();
        }
        ensureLoaded();
        List<String> cleaned = clean(tags);
        String key = PathKey.of(path);
        if (cleaned.isEmpty()) {
            index.remove(key);
            originalPaths.remove(key);
        } else {
            index.put(key, cleaned);
            originalPaths.put(key, path.toAbsolutePath().normalize().toString());
        }
        persist();
        return List.copyOf(cleaned);
    }

    /** 添加一个标签（已存在则不动，返回是否发生了新增）。 */
    public boolean addTag(Path path, String tag) {
        String normalized = normalize(tag);
        if (path == null || normalized == null) {
            return false;
        }
        List<String> current = new ArrayList<>(tagsOf(path));
        if (current.contains(normalized)) {
            return false;
        }
        if (current.size() >= MAX_TAGS_PER_FILE) {
            return false;
        }
        current.add(normalized);
        setTags(path, current);
        return true;
    }

    /** 移除一个标签。 */
    public boolean removeTag(Path path, String tag) {
        String normalized = normalize(tag);
        if (path == null || normalized == null) {
            return false;
        }
        List<String> current = new ArrayList<>(tagsOf(path));
        if (!current.remove(normalized)) {
            return false;
        }
        setTags(path, current);
        return true;
    }

    /** 批量添加标签到多个文件。 */
    public int addTagToAll(List<Path> paths, String tag) {
        if (paths == null) {
            return 0;
        }
        int changed = 0;
        for (Path p : paths) {
            if (addTag(p, tag)) {
                changed++;
            }
        }
        return changed;
    }

    /** 重命名后迁移标签。 */
    public boolean remapPath(Path oldPath, Path newPath) {
        if (oldPath == null || newPath == null) {
            return false;
        }
        ensureLoaded();
        String oldKey = PathKey.of(oldPath);
        List<String> tags = index.remove(oldKey);
        if (tags == null) {
            return false;
        }
        originalPaths.remove(oldKey);
        String newKey = PathKey.of(newPath);
        index.put(newKey, tags);
        originalPaths.put(newKey, newPath.toAbsolutePath().normalize().toString());
        persist();
        return true;
    }

    /** 移除某个文件的全部标签。 */
    public boolean remove(Path path) {
        ensureLoaded();
        String key = PathKey.of(path);
        boolean removed = index.remove(key) != null;
        originalPaths.remove(key);
        if (removed) {
            persist();
        }
        return removed;
    }

    /**
     * 当前存在的全部标签（按使用次数降序、同次数按名称排序）。
     *
     * <p>从现有映射<b>推导</b>而不是单独维护：没有文件使用的标签自然就不存在。
     */
    public List<String> allTags() {
        return new ArrayList<>(tagCounts().keySet());
    }

    /** 标签 -> 使用次数，用于侧栏展示。 */
    public Map<String, Integer> tagCounts() {
        ensureLoaded();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (List<String> tags : index.values()) {
            for (String tag : tags) {
                counts.merge(tag, 1, Integer::sum);
            }
        }
        // 次数降序；同次数按名称排序，保证侧栏顺序稳定（否则每次刷新都可能换位置）
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Comparator
                .comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed()
                .thenComparing(Map.Entry::getKey));
        Map<String, Integer> result = new LinkedHashMap<>();
        sorted.forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    /** 打了任一标签的文件数。 */
    public int taggedFileCount() {
        ensureLoaded();
        return index.size();
    }

    /** 清理指向已不存在文件的标签。 */
    public int pruneMissing(java.util.function.Predicate<Path> stillExists) {
        ensureLoaded();
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, String> e : originalPaths.entrySet()) {
            try {
                if (!stillExists.test(Path.of(e.getValue()))) {
                    toRemove.add(e.getKey());
                }
            } catch (RuntimeException ignored) {
                // 路径非法：保守保留
            }
        }
        toRemove.forEach(key -> {
            index.remove(key);
            originalPaths.remove(key);
        });
        if (!toRemove.isEmpty()) {
            persist();
        }
        return toRemove.size();
    }

    public void clear() {
        ensureLoaded();
        index.clear();
        originalPaths.clear();
        persist();
    }

    /** 规范化单个标签：去首尾空白、限长；空或超长返回 null。 */
    public static String normalize(String tag) {
        if (tag == null) {
            return null;
        }
        String t = tag.trim().replaceAll("\\s+", " ");
        if (t.isEmpty() || t.length() > MAX_TAG_LENGTH) {
            return null;
        }
        return t;
    }

    private static List<String> clean(List<String> tags) {
        Set<String> out = new LinkedHashSet<>();
        if (tags != null) {
            for (String tag : tags) {
                String t = normalize(tag);
                if (t != null) {
                    out.add(t);
                }
                if (out.size() >= MAX_TAGS_PER_FILE) {
                    break;
                }
            }
        }
        return new ArrayList<>(out);
    }

    private void persist() {
        List<Entry> list = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : index.entrySet()) {
            list.add(Entry.of(Path.of(originalPaths.getOrDefault(e.getKey(), e.getKey())), e.getValue()));
        }
        list.sort(Comparator.comparing(entry -> entry.path));
        write(list);
    }

    /** 供自检使用：内部映射规模（应等于 {@link #taggedFileCount()}）。 */
    public int size() {
        ensureLoaded();
        return index.size();
    }

    /** 供自检/调试：标签到文件的倒排（便于回答"哪些文件带这个标签"）。 */
    public List<Path> filesWithTag(String tag) {
        String normalized = normalize(tag);
        if (normalized == null) {
            return List.of();
        }
        ensureLoaded();
        List<Path> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : index.entrySet()) {
            if (e.getValue().contains(normalized)) {
                result.add(Path.of(originalPaths.getOrDefault(e.getKey(), e.getKey())));
            }
        }
        return result;
    }

    /** 供自检使用：按标签名排序的全部标签（不含使用次数），用于稳定断言。 */
    public Map<String, Integer> tagCountsSortedByName() {
        return new TreeMap<>(tagCounts());
    }
}
