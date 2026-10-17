package com.xmedia.downloader.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.xmedia.downloader.account.Account;
import com.xmedia.downloader.account.AccountPool;
import com.xmedia.downloader.client.TimelineParser;
import com.xmedia.downloader.config.AppConfig;
import com.xmedia.downloader.downloader.MediaDownloader;
import com.xmedia.downloader.model.DownloadRecord;
import com.xmedia.downloader.model.MediaItem;
import com.xmedia.downloader.model.RecordStatus;
import com.xmedia.downloader.model.TaskResult;
import com.xmedia.downloader.model.TaskType;
import com.xmedia.downloader.model.Tweet;
import com.xmedia.downloader.ratelimit.QuotaLedger;
import com.xmedia.downloader.storage.DownloadRecordRepository;
import com.xmedia.downloader.storage.SyncCursorRepository;
import com.xmedia.downloader.storage.TaskRunRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 时间线类任务基类（用户媒体 / Tag 搜索 / 评论区 / 纯文本共用）。
 *
 * <p>把「翻页 → 过滤 → 去重 → 并发下载 → 落库 → 水位线 → 汇总」这套已经真机验证过的逻辑集中在这里，
 * 子类只需实现「怎么取一页」与少量钩子（非功能需求：新增任务类型无需大幅修改核心代码）。
 *
 * <p>三条已踩过坑的约束（都有测试守）：
 * <ul>
 *   <li>「已处理」判定用**初始水位线**，回写用**本轮最大 ID**，两者绝不能共用变量；</li>
 *   <li>整页无推文即到底（服务端可能回「空页 + 游标」）；游标重复也到底；另有页数兜底；</li>
 *   <li>dry-run 绝不写水位线/记录。</li>
 * </ul>
 */
public abstract class AbstractTimelineTask {

    protected static final Logger log = LoggerFactory.getLogger(AbstractTimelineTask.class);
    private static final int OLDER_STREAK_STOP = 20;
    private static final int MAX_PAGES = 300;
    protected static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** @param dumpPages 非空时把每页原始 JSON 落盘（排查解析问题 / 生成测试夹具） */
    public record Options(int limitTweets, boolean dryRun, boolean fresh, Path dumpPages) {

        public static Options of(int limitTweets, boolean dryRun) {
            return new Options(limitTweets, dryRun, false, null);
        }
    }

    /**
     * 子类准备阶段的结果。
     *
     * @param displayName 日志展示名（如 @user 或 #tag）
     * @param cursorKey   sync_cursor 的键；返回 null 表示该任务不使用水位线
     */
    protected record Target(String displayName, String cursorKey) {
    }

    protected final AppConfig config;
    protected final AccountPool pool;
    protected final TimelineParser parser;
    protected final MediaDownloader downloader;
    protected final DownloadRecordRepository records;
    protected final SyncCursorRepository cursors;
    protected final TaskRunRepository taskRuns;
    protected final QuotaLedger quota;
    protected final Path saveRoot;

    protected AbstractTimelineTask(AppConfig config, AccountPool pool, TimelineParser parser,
                                   MediaDownloader downloader, DownloadRecordRepository records,
                                   SyncCursorRepository cursors, TaskRunRepository taskRuns,
                                   QuotaLedger quota) {
        this.config = config;
        this.pool = pool;
        this.parser = parser;
        this.downloader = downloader;
        this.records = records;
        this.cursors = cursors;
        this.taskRuns = taskRuns;
        this.quota = quota;
        this.saveRoot = Path.of(config.savePath());
    }

    protected abstract TaskType taskType();

    /** 准备阶段：解析用户 / 校验参数；可抛异常终止任务。 */
    protected abstract Target prepare(Account account, String target);

    /** 取一页（cursor 为 null 表示首页）。 */
    protected abstract TimelineParser.Page fetchPage(Account account, String target, String cursor);

    /** 该任务是否需要下载媒体（纯文本任务返回 false）。 */
    protected boolean downloadsMedia() {
        return true;
    }

    /**
     * 每条推文的额外处理：媒体任务里用于「Tag 同时保存文本」等附加动作；
     * 纯文本任务（{@link #downloadsMedia()} 返回 false）里它就是主要动作。
     * <p>注意：dry-run 不会调用本方法（避免只列清单也落盘）。
     */
    protected void onTweet(Tweet tweet, Target target) {
    }

    /** 媒体落盘时的序号起点（子类可覆盖），默认全局自增。 */
    protected int indexBase() {
        return 0;
    }

    /** 每页条数（子类按任务类型覆盖）。 */
    protected int pageSize() {
        return 20;
    }

    /** 媒体落盘根目录（子类可覆盖，例如 Tag 任务按查询词建目录）。 */
    protected Path downloadRoot() {
        return saveRoot;
    }

    public TaskResult run(String target, Options options) {
        long startedAt = System.currentTimeMillis();
        Account account = pool.acquire();
        Target prepared = prepare(account, target);
        int apiCalls = 1;   // prepare 里通常已发一次请求（解析用户等）
        System.out.println("=== 开始处理 " + prepared.displayName() + "（" + taskType() + "） ===");

        // dry-run 保持"零状态"：不写任务行（运行过程本身在控制台可见）；配额账本照记，因为确实消耗了 API 调用
        long runId = options.dryRun() ? -1L : taskRuns.start(taskType().name(), prepared.displayName());
        int success = 0;
        int failed = 0;
        int skipped = 0;
        int processedTweets = 0;
        int olderStreak = 0;
        int pageCount = 0;
        int index = indexBase();
        boolean done = false;
        String resumeCursor = null;
        List<String> failures = new ArrayList<>();
        Set<String> seenCursors = new HashSet<>();

        String cursor = null;
        // 「初始水位线」用于判定已处理；「本轮最大 ID」用于回写 —— 两者必须分开（详见类注释）
        String initialWatermark = config.download().enableIncremental() && !options.fresh()
                && prepared.cursorKey() != null ? cursors.lastMaxTweetId(prepared.cursorKey()) : null;
        String maxSeenId = initialWatermark;
        if (initialWatermark != null) {
            log.info("增量模式：{} 上次水位线 {}", prepared.displayName(), initialWatermark);
        }

        ExecutorService workers = Executors.newFixedThreadPool(config.download().maxConcurrent());
        try {
            while (!done) {
                if (quota.remaining(account) <= 0) {
                    resumeCursor = cursor;
                    System.err.println("配额已耗尽，停机。下次运行可从此续跑点继续：" + cursor);
                    break;
                }
                TimelineParser.Page parsed = fetchPage(account, target, cursor);
                if (options.dumpPages() != null) {
                    dumpPage(options.dumpPages(), apiCalls, parsed);
                }
                apiCalls++;
                if (++pageCount > MAX_PAGES) {
                    log.warn("已达最大页数 {}，停止翻页", MAX_PAGES);
                    break;
                }
                if (initialWatermark != null) {
                    String pageMax = parsed.tweets().stream().map(Tweet::tweetId)
                            .max(AbstractTimelineTask::compareId).orElse(null);
                    if (pageMax != null && compareId(pageMax, initialWatermark) <= 0) {
                        log.info("本页最新推文 {} 未超过水位线 {}，提前终止（省配额）", pageMax, initialWatermark);
                        done = true;
                        break;
                    }
                }
                if (parsed.tweets().isEmpty()) {
                    break;
                }

                for (Tweet tweet : parsed.tweets()) {
                    if (options.limitTweets() > 0 && processedTweets >= options.limitTweets()) {
                        done = true;
                        break;
                    }
                    if (tweet.createdAtMillis() > 0
                            && !config.download().timeRange().contains(tweet.createdAtMillis())) {
                        if (tweet.createdAtMillis() < config.download().timeRange().startMillis()) {
                            if (++olderStreak >= OLDER_STREAK_STOP) {
                                done = true;
                                break;
                            }
                        }
                        continue;
                    }
                    if (initialWatermark != null && compareId(tweet.tweetId(), initialWatermark) <= 0) {
                        if (++olderStreak >= OLDER_STREAK_STOP) {
                            done = true;
                            break;
                        }
                        continue;
                    }
                    olderStreak = 0;
                    processedTweets++;
                    if (maxSeenId == null || compareId(tweet.tweetId(), maxSeenId) > 0) {
                        maxSeenId = tweet.tweetId();
                    }

                    List<MediaItem> items = downloadsMedia() ? new ArrayList<>(tweet.mediaItems()) : List.of();
                    if (options.dryRun()) {
                        if (!items.isEmpty()) {
                            for (MediaItem item : items) {
                                skipped++;
                                System.out.println("  [列出] " + item.type().label() + " "
                                        + LOG_TIME.format(Instant.ofEpochMilli(tweet.createdAtMillis())
                                        .atZone(ZoneId.systemDefault()))
                                        + "  @" + item.userScreenName() + "  " + item.url());
                            }
                        } else {
                            skipped++;
                            System.out.println("  [列出] 文本 " + LOG_TIME.format(Instant.ofEpochMilli(
                                    tweet.createdAtMillis()).atZone(ZoneId.systemDefault()))
                                    + "  @" + tweet.userScreenName() + "  " + tweet.tweetId());
                        }
                        continue;
                    }
                    if (!downloadsMedia()) {
                        // 纯文本任务：写出文本即算一条成功（媒体任务走下面的下载分支）
                        onTweet(tweet, prepared);
                        success++;
                        continue;
                    }
                    onTweet(tweet, prepared);
                    if (items.isEmpty()) {
                        continue;
                    }

                    if (config.download().enableIncremental()) {
                        Set<String> already = records.downloadedKeys(items);
                        int before = items.size();
                        items.removeIf(i -> already.contains(DownloadRecordRepository.key(i)));
                        skipped += before - items.size();
                    }
                    if (items.isEmpty()) {
                        continue;
                    }

                    List<Future<DownloadRecord>> futures = new ArrayList<>();
                    for (MediaItem item : items) {
                        final int idx = index++;
                        futures.add(workers.submit(() -> {
                            MediaDownloader.Outcome outcome = downloader.download(item, downloadRoot(), idx);
                            DownloadRecord record = toRecord(item, tweet, outcome);
                            records.upsert(record);
                            return record;
                        }));
                    }
                    for (Future<DownloadRecord> future : futures) {
                        try {
                            DownloadRecord record = future.get();
                            if (record.status() == RecordStatus.SUCCESS) {
                                success++;
                                log.info("下载完成：{}（{} 字节）", record.localPath(), record.fileSize());
                            } else {
                                failed++;
                                failures.add(record.localPath() + " ← " + record.errorMessage());
                                log.warn("下载失败：{} ← {}", record.localPath(), record.errorMessage());
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            failed++;
                        } catch (Exception e) {
                            failed++;
                            failures.add("任务异常：" + e.getMessage());
                        }
                    }
                }

                if (parsed.nextCursor() == null || parsed.nextCursor().isBlank()
                        || !seenCursors.add(parsed.nextCursor())) {
                    done = true;
                } else {
                    cursor = parsed.nextCursor();
                }
            }
        } finally {
            workers.shutdown();
            try {
                workers.awaitTermination(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (options.dryRun()) {
            log.info("dry-run：未写入水位线与下载记录");
        } else if (maxSeenId != null && prepared.cursorKey() != null) {
            cursors.save(new SyncCursorRepository.Cursor(prepared.cursorKey(), prepared.displayName(),
                    maxSeenId, Instant.now().toString(), runId));
        }
        long elapsed = System.currentTimeMillis() - startedAt;
        TaskResult.Status status = failed == 0 ? TaskResult.Status.SUCCESS
                : (success > 0 ? TaskResult.Status.PARTIAL : TaskResult.Status.FAILED);
        if (resumeCursor != null) {
            status = TaskResult.Status.ABORTED;
        }
        if (runId > 0) {   // dry-run 无任务行可收尾
            taskRuns.finish(runId, status, success + failed + skipped, success, failed, skipped, apiCalls, elapsed,
                    resumeCursor == null ? "" : "配额耗尽，续跑点 " + resumeCursor);
        }
        return new TaskResult(taskType() + ":" + prepared.displayName(), status, success, failed, skipped,
                elapsed, failures, resumeCursor, apiCalls);
    }

    /** 原始响应落盘（dry-run 排查解析问题用）。 */
    private static void dumpPage(Path dir, int seq, TimelineParser.Page page) {
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(String.format("page-%02d.json", seq));
            JsonNode raw = page.raw();
            Files.writeString(file, raw == null ? "{}" : raw.toPrettyString(), StandardCharsets.UTF_8);
            log.info("已导出原始响应：{}", file.toAbsolutePath());
        } catch (IOException e) {
            log.warn("导出原始响应失败：{}", e.getMessage());
        }
    }

    private DownloadRecord toRecord(MediaItem item, Tweet tweet, MediaDownloader.Outcome outcome) {
        String createdAt = tweet.createdAtMillis() == 0 ? null
                : Instant.ofEpochMilli(tweet.createdAtMillis()).toString();
        return new DownloadRecord(item.tweetId(), item.mediaKey(), item.type(), item.url(),
                tweet.userRestId(), item.userScreenName(), taskType(),
                outcome.success() ? RecordStatus.SUCCESS : RecordStatus.FAILED,
                outcome.path() == null ? null : outcome.path().toString(),
                outcome.success() ? null : outcome.error(),
                outcome.bytes(), item.variantBitrate(), createdAt,
                outcome.success() ? Instant.now().toString() : null,
                DownloadRecord.DEFAULT_ATTEMPT);
    }

    /** 推文 ID 是雪花号，可用 long 比较（异常长度退回字符串比较）。 */
    static int compareId(String a, String b) {
        try {
            return Long.compare(Long.parseLong(a), Long.parseLong(b));
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }

    /** 便于子类复用：把原始 JsonNode 包成带原文的 Page（供 dumpPages 使用）。 */
    protected static TimelineParser.Page withRaw(TimelineParser.Page page, JsonNode raw) {
        return new TimelineParser.Page(page.tweets(), page.nextCursor(), page.topReached(), raw);
    }
}
