package com.zean.filepanel.store;

import com.fasterxml.jackson.core.type.TypeReference;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量创建的撤销日志。
 *
 * <p>和 {@link RenameJournal} 同一个思路、同一个理由：批量操作一旦规则写错，
 * 手工一个个删回去很痛苦。批量创建虽然不覆盖任何东西（危害小得多），
 * 但"手滑建了 2000 个文件夹"同样需要一键收拾。
 *
 * <p>只保留最近 {@value #MAX_BATCHES} 次，且与重命名日志<b>分开存</b>：
 * 两者撤销的动作完全不同（一个是改回原名、一个是删除），混在一份日志里就得靠类型字段区分，
 * 很容易在"撤销"入口上出岔子——撤销重命名把刚建的文件删了，是不可接受的错误。
 */
public final class CreateJournal extends JsonFileStore {

    public static final String FILE_NAME = "create-journal.json";
    public static final int MAX_BATCHES = 20;

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 一批创建出来的东西。 */
    public static final class Batch {
        public String id;
        public String time;
        public String description;
        /** 本次创建出来的绝对路径。<b>只记成功的</b>，失败的不会被撤销到。 */
        public List<String> paths = new ArrayList<>();

        public int size() {
            return paths == null ? 0 : paths.size();
        }

        public List<Path> pathList() {
            List<Path> out = new ArrayList<>();
            if (paths != null) {
                for (String p : paths) {
                    out.add(Path.of(p));
                }
            }
            return out;
        }
    }

    public CreateJournal(Path dataDirectory) {
        super(dataDirectory, FILE_NAME);
    }

    /**
     * 记录一次成功的批量创建。
     *
     * @return 本次操作的 id，供撤销后从日志中移除
     */
    public String record(String description, List<Path> createdPaths) {
        Batch batch = new Batch();
        batch.id = "c" + Long.toHexString(System.nanoTime()) + "-" + System.currentTimeMillis();
        batch.time = LocalDateTime.now().format(TIME_FORMAT);
        batch.description = description;
        for (Path p : createdPaths) {
            batch.paths.add(p.toAbsolutePath().normalize().toString());
        }

        List<Batch> all = new ArrayList<>(all());
        all.add(batch);
        if (all.size() > MAX_BATCHES) {
            all = new ArrayList<>(all.subList(all.size() - MAX_BATCHES, all.size()));
        }
        write(all);
        return batch.id;
    }

    public List<Batch> all() {
        List<Batch> list = readOrNull(new TypeReference<List<Batch>>() {
        });
        return list == null ? List.of() : list;
    }

    /** 最近一次可撤销的创建；没有则返回空。 */
    public java.util.Optional<Batch> last() {
        List<Batch> all = all();
        return all.isEmpty() ? java.util.Optional.empty()
                : java.util.Optional.of(all.get(all.size() - 1));
    }

    /** 撤销完成后把这条记录移除，避免重复撤销（第二次会去删已经不存在的东西）。 */
    public boolean removeBatch(String id) {
        if (id == null) {
            return false;
        }
        List<Batch> all = new ArrayList<>(all());
        boolean removed = all.removeIf(b -> id.equals(b.id));
        if (removed) {
            write(all);
        }
        return removed;
    }

    /** 供界面展示："可撤销：新建 12 个文件夹（10:31:07）"。 */
    public String describeLast() {
        return last().map(b -> "可撤销：" + b.description + "（" + b.time + "）")
                .orElse("没有可撤销的批量创建");
    }
}
