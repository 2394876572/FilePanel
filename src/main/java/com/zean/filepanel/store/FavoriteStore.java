package com.zean.filepanel.store;

import com.fasterxml.jackson.core.type.TypeReference;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 收藏。
 *
 * <p>存的是"路径集合"，查表用 {@link PathKey} 归一化后的键——
 * Windows 忽略大小写，若不归一化，用户收藏了 {@code 报告.DOCX}，
 * 界面上显示为 {@code 报告.docx} 时星标就会是空的。
 *
 * <p>与最近使用一样，重命名后必须迁移，否则"收藏"会在改名后凭空消失。
 */
public final class FavoriteStore extends JsonFileStore {

    public static final String FILE_NAME = "favorites.json";

    /** 一条收藏记录。保留收藏时间，便于将来按收藏顺序排列。 */
    public static final class Entry {
        public String path;
        public long timestamp;

        public Entry() {
        }

        public static Entry of(Path path) {
            Entry entry = new Entry();
            entry.path = path.toAbsolutePath().normalize().toString();
            entry.timestamp = System.currentTimeMillis();
            return entry;
        }
    }

    private final Set<String> keys = new LinkedHashSet<>();
    private final List<Entry> entries = new ArrayList<>();
    private boolean loaded;

    public FavoriteStore(Path dataDirectory) {
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
                if (e != null && e.path != null) {
                    String key = PathKey.of(Path.of(e.path));
                    if (keys.add(key)) {
                        entries.add(e);
                    }
                }
            }
        }
    }

    public boolean isFavorite(Path path) {
        ensureLoaded();
        return keys.contains(PathKey.of(path));
    }

    /** 设置收藏状态。 */
    public void setFavorite(Path path, boolean favorite) {
        if (path == null) {
            return;
        }
        ensureLoaded();
        String key = PathKey.of(path);
        if (favorite) {
            if (keys.add(key)) {
                entries.add(Entry.of(path));
                persist();
            }
        } else if (keys.remove(key)) {
            entries.removeIf(e -> PathKey.of(Path.of(e.path)).equals(key));
            persist();
        }
    }

    /** 切换收藏状态，返回切换后的值。 */
    public boolean toggle(Path path) {
        boolean next = !isFavorite(path);
        setFavorite(path, next);
        return next;
    }

    /** 重命名后迁移收藏。 */
    public boolean remapPath(Path oldPath, Path newPath) {
        if (oldPath == null || newPath == null) {
            return false;
        }
        ensureLoaded();
        String oldKey = PathKey.of(oldPath);
        if (!keys.remove(oldKey)) {
            return false;
        }
        String newKey = PathKey.of(newPath);
        keys.add(newKey);
        for (Entry e : entries) {
            if (PathKey.of(Path.of(e.path)).equals(oldKey)) {
                e.path = newPath.toAbsolutePath().normalize().toString();
            }
        }
        persist();
        return true;
    }

    public boolean remove(Path path) {
        ensureLoaded();
        String key = PathKey.of(path);
        boolean removed = keys.remove(key);
        if (removed) {
            entries.removeIf(e -> PathKey.of(Path.of(e.path)).equals(key));
            persist();
        }
        return removed;
    }

    /** 全部收藏路径（按收藏时间升序）。 */
    public List<Path> paths() {
        ensureLoaded();
        return entries.stream().map(e -> Path.of(e.path)).toList();
    }

    public int size() {
        ensureLoaded();
        return keys.size();
    }

    public void clear() {
        ensureLoaded();
        keys.clear();
        entries.clear();
        persist();
    }

    private void persist() {
        write(new ArrayList<>(entries));
    }
}
