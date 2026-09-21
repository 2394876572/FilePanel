package com.zean.filepanel.store;

import com.fasterxml.jackson.core.type.TypeReference;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 删除操作日志。
 *
 * <p>永久删除不可撤销，但至少要<b>可追溯</b>：用户事后能查到"我到底删了什么、什么时候删的"，
 * 这往往就是找回文件的唯一线索。
 *
 * <p>同时记录回收站删除，因为"我删过这个文件吗"这个问题对两种删除同样成立，
 * 而 {@code mode} 字段能区分它们是否还能从回收站找回。
 *
 * <p>容量上限 {@value #MAX_ENTRIES} 条：这是一个排查线索，不是审计系统，
 * 无限增长只会让文件越来越大且读取越来越慢。
 */
public final class DeleteJournal extends JsonFileStore {

    public static final String FILE_NAME = "delete-journal.json";
    public static final int MAX_ENTRIES = 500;

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 一条删除记录。 */
    public record Entry(String path, long size, boolean directory, String mode, String time) {

        public static Entry of(Path path, long size, boolean directory, String mode) {
            return new Entry(path.toAbsolutePath().toString(), size, directory, mode,
                    LocalDateTime.now().format(TIME_FORMAT));
        }

        /** {@code RECYCLE} 表示还能从回收站找回，{@code PERMANENT} 表示已不可恢复。 */
        public boolean recoverable() {
            return "RECYCLE".equals(mode);
        }
    }

    /** @param dataDirectory 数据目录，通常是 {@code <根目录>/.filepanel} */
    public DeleteJournal(Path dataDirectory) {
        super(dataDirectory, FILE_NAME);
    }

    /** 追加若干条记录；任何失败都只记日志，绝不影响删除流程本身。 */
    public void append(List<Entry> entries) {
        if (entries == null || entries.isEmpty() || file() == null) {
            return;
        }
        List<Entry> all = new ArrayList<>(read());
        all.addAll(entries);
        if (all.size() > MAX_ENTRIES) {
            all = new ArrayList<>(all.subList(all.size() - MAX_ENTRIES, all.size()));
        }
        write(all);
    }

    /** 读取全部记录；文件不存在或损坏时返回空列表。 */
    public List<Entry> read() {
        List<Entry> list = readOrNull(new TypeReference<List<Entry>>() {
        });
        return list == null ? List.of() : list;
    }

    /** 最近 N 条（最新的在前）。 */
    public List<Entry> recent(int limit) {
        List<Entry> all = new ArrayList<>(read());
        Collections.reverse(all);
        return all.size() <= limit ? all : all.subList(0, limit);
    }
}
