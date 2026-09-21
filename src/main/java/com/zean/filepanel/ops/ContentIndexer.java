package com.zean.filepanel.ops;

import com.zean.filepanel.core.FileItem;
import com.zean.filepanel.store.ContentIndex;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 后台内容索引器。
 *
 * <h2>为什么必须后台、且必须可取消</h2>
 * 抽取一个几百 KB 的 docx 就要几十毫秒，一整个目录下来是秒级甚至分钟级。
 * 放在 UI 线程上做，界面直接卡死；不给取消，用户就只能干等。
 *
 * <h2>为什么单文件要有超时</h2>
 * 体积上限（5 MB）已经排除了绝大多数"慢"的情况，但 PDF 是个例外：
 * 几 MB 的 PDF 也可能有几万页，PDFBox 能解析很久。
 * 所以抽取放在独立的单线程执行器里，超时就把这个执行器整个换掉——
 * 因为原生解析器往往不响应中断，原地"等它停"是等不到的，而卡住的线程不该拖住后续文件。
 *
 * <h2>增量</h2>
 * 已经索引过且"大小 + 修改时间"没变的文件直接跳过，所以第二次建立索引几乎瞬间完成。
 */
public final class ContentIndexer {

    /** 单文件抽取超时。 */
    public static final long EXTRACT_TIMEOUT_MS = 3000;

    /** 进度回调的节流：每处理这么多文件报一次。 */
    private static final int PROGRESS_STEP = 20;

    /** 索引进度与结果。 */
    public interface Listener {
        /** @param done 已处理文件数 @param total 待处理总数 @param current 当前文件名 */
        void onProgress(int done, int total, String current);

        /**
         * @param indexed  新建立索引的文件数
         * @param upToDate 已有有效索引、本次直接复用的文件数
         * @param failed   抽取失败或没有可索引文字的文件数
         * @param stopped  是否因达到索引容量上限而提前停止
         */
        void onFinished(int indexed, int upToDate, int failed, boolean stopped);
    }

    private final ContentIndex index;
    private final ExecutorService worker;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 专职做抽取的执行器；超时后会被整个替换（见类注释）。 */
    private ExecutorService extractor = newExtractor();

    public ContentIndexer(ContentIndex index) {
        this.index = index;
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread t = new Thread(runnable, "filepanel-content-indexer");
            t.setDaemon(true);
            return t;
        });
    }

    private static ExecutorService newExtractor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread t = new Thread(runnable, "filepanel-content-extract");
            t.setDaemon(true);
            return t;
        });
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * 开始建立索引。
     *
     * @param items    候选条目（通常是一次扫描的全部结果）
     * @param listener 进度回调；会在<b>后台线程</b>上被调用，调用方需自行切回 UI 线程
     */
    public void start(List<FileItem> items, Listener listener) {
        if (items == null || items.isEmpty()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            // 已经在跑：忽略这次请求，避免同一份索引被两个线程同时改
            return;
        }
        cancelled.set(false);

        List<FileItem> candidates = eligible(items);
        worker.submit(() -> {
            int indexed = 0;
            int upToDate = 0;
            int failed = 0;
            boolean stopped = false;
            try {
                int total = candidates.size();
                int done = 0;
                for (FileItem item : candidates) {
                    if (cancelled.get()) {
                        break;
                    }
                    if (index.isFull()) {
                        stopped = true;
                        break;
                    }
                    if (index.isIndexed(item)) {
                        // 与"抽取失败"分开计数：这一步是好事（增量生效），
                        // 混在一起报"跳过 N 个"会让用户以为有 N 个文件出错了
                        upToDate++;
                    } else {
                        ContentExtractor.Extraction extraction = extractWithTimeout(item.path());
                        if (extraction.succeeded()) {
                            long modified = item.modified() == null ? 0L : item.modified().toMillis();
                            if (index.put(item.path(), item.size(), modified,
                                    extraction.text(), extraction.truncated())) {
                                indexed++;
                            } else {
                                stopped = true;
                                break;
                            }
                        } else {
                            failed++;
                        }
                    }
                    done++;
                    if (done % PROGRESS_STEP == 0 || done == total) {
                        listener.onProgress(done, total, item.name());
                    }
                }
                index.save();
            } catch (RuntimeException e) {
                System.err.println("[ContentIndexer] 建索引时出错（已索引部分仍可用）：" + e);
            } finally {
                running.set(false);
                listener.onFinished(indexed, upToDate, failed, stopped);
            }
        });
    }

    /** 请求取消。已经在抽取的那个文件会跑完（原生解析器多半不响应中断）。 */
    public void cancel() {
        cancelled.set(true);
    }

    /** 关闭后台线程，供窗口关闭时调用。 */
    public void shutdown() {
        cancelled.set(true);
        worker.shutdownNow();
        extractor.shutdownNow();
    }

    /**
     * 挑出值得抽取的条目：非目录、类型受支持、体积不超上限。
     *
     * <p>公开是因为命令行验证模式（{@code --content-search}）也要用同一套筛选规则：
     * 两处各写一份过滤条件，迟早会出现"界面索引了但命令行说没索引"这种自相矛盾的结果。
     */
    public static List<FileItem> eligible(List<FileItem> items) {
        List<FileItem> out = new ArrayList<>();
        for (FileItem item : items) {
            if (!ContentExtractor.isSupported(item)) {
                continue;
            }
            if (item.size() > ContentExtractor.MAX_FILE_BYTES) {
                continue;
            }
            out.add(item);
        }
        return out;
    }

    /** 带超时的抽取。超时后替换执行器，避免卡住的线程拖住后面的文件。 */
    ContentExtractor.Extraction extractWithTimeout(Path path) {
        Future<ContentExtractor.Extraction> future =
                extractor.submit(() -> ContentExtractor.extract(path));
        try {
            return future.get(EXTRACT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            extractor.shutdownNow();
            extractor = newExtractor();
            return ContentExtractor.Extraction.skipped(ContentExtractor.Skip.ERROR,
                    "解析超过 " + EXTRACT_TIMEOUT_MS + " ms，已跳过");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ContentExtractor.Extraction.skipped(ContentExtractor.Skip.ERROR, "被中断");
        } catch (ExecutionException e) {
            return ContentExtractor.Extraction.skipped(ContentExtractor.Skip.ERROR,
                    String.valueOf(e.getCause()));
        }
    }
}
