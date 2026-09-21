package com.zean.filepanel.store;

import com.fasterxml.jackson.core.type.TypeReference;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量重命名的撤销日志。
 *
 * <p>批量重命名是"一次操作改动很多文件"的典型场景，一旦规则写错，
 * 手工一个个改回来几乎不可能。所以每次成功的批量操作都记下完整的
 * <b>改名前后对照</b>，并允许整体撤销。
 *
 * <p>只保留最近 {@value #MAX_BATCHES} 次：这是一个"刚才手抖了"的补救措施，
 * 不是版本控制。保留太多只会让文件变大、撤销入口变得难以理解（"撤销到哪一次？"）。
 */
public final class RenameJournal extends JsonFileStore {

    public static final String FILE_NAME = "rename-journal.json";
    public static final int MAX_BATCHES = 20;

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 一次改名操作的前后对照。 */
    public static final class Pair {
        public String from;
        public String to;

        public Pair() {
        }

        public static Pair of(Path from, Path to) {
            Pair pair = new Pair();
            pair.from = from.toAbsolutePath().normalize().toString();
            pair.to = to.toAbsolutePath().normalize().toString();
            return pair;
        }

        public Path fromPath() {
            return Path.of(from);
        }

        public Path toPath() {
            return Path.of(to);
        }
    }

    /** 一批操作。 */
    public static final class Batch {
        public String id;
        public String time;
        public String description;
        public List<Pair> pairs = new ArrayList<>();

        public int size() {
            return pairs == null ? 0 : pairs.size();
        }
    }

    public RenameJournal(Path dataDirectory) {
        super(dataDirectory, FILE_NAME);
    }

    /**
     * 记录一次成功的批量重命名。
     *
     * @return 本次操作的 id，供撤销后从日志中移除
     */
    public String record(String description, List<Pair> pairs) {
        Batch batch = new Batch();
        batch.id = Long.toHexString(System.nanoTime()) + "-" + System.currentTimeMillis();
        batch.time = LocalDateTime.now().format(TIME_FORMAT);
        batch.description = description;
        batch.pairs = new ArrayList<>(pairs);

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

    /** 最近一次可撤销的操作；没有则返回空。 */
    public java.util.Optional<Batch> last() {
        List<Batch> all = all();
        return all.isEmpty() ? java.util.Optional.empty()
                : java.util.Optional.of(all.get(all.size() - 1));
    }

    /** 撤销完成后把这条记录移除，避免重复撤销。 */
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

    /** 供界面展示："可撤销：把 12 个文件改回原名（10:31:07）"。 */
    public String describeLast() {
        return last().map(b -> "可撤销：" + b.description + "（" + b.time + "）")
                .orElse("没有可撤销的重命名");
    }
}
