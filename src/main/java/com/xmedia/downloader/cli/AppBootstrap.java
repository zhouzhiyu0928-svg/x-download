package com.xmedia.downloader.cli;

import com.xmedia.downloader.account.AccountPool;
import com.xmedia.downloader.client.GraphQLClient;
import com.xmedia.downloader.client.OperationsConfig;
import com.xmedia.downloader.client.TimelineParser;
import com.xmedia.downloader.client.UserApi;
import com.xmedia.downloader.config.AppConfig;
import com.xmedia.downloader.downloader.MediaDownloader;
import com.xmedia.downloader.task.ProfileTask;
import com.xmedia.downloader.task.ReplyTask;
import com.xmedia.downloader.task.TagSearchTask;
import com.xmedia.downloader.task.TextTask;
import com.xmedia.downloader.task.UserMediaTask;
import com.xmedia.downloader.ratelimit.QuotaLedger;
import com.xmedia.downloader.ratelimit.RateLimiter;
import com.xmedia.downloader.storage.Database;
import com.xmedia.downloader.storage.DownloadRecordRepository;
import com.xmedia.downloader.storage.QuotaLedgerRepository;
import com.xmedia.downloader.storage.SyncCursorRepository;
import com.xmedia.downloader.storage.TargetRepository;
import com.xmedia.downloader.target.TargetListService;
import com.xmedia.downloader.task.AbstractTimelineTask;
import com.xmedia.downloader.task.TargetListTask;
import com.xmedia.downloader.storage.TaskRunRepository;
import com.xmedia.downloader.util.NamingTemplate;
import java.nio.file.Path;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 依赖装配（P6）：Command 只解析参数并调用本类，避免每个子命令重复装配。
 * R2/R3 会在这里补 GraphQLClient / MediaDownloader / TaskScheduler。
 */
public final class AppBootstrap implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AppBootstrap.class);

    private final AppConfig config;
    private final Database database;
    private final DownloadRecordRepository records;
    private final SyncCursorRepository cursors;
    private final QuotaLedgerRepository quotaRepository;
    private final AccountPool accountPool;
    private final RateLimiter rateLimiter;
    private final QuotaLedger quotaLedger;
    private final NamingTemplate namingTemplate;
    private final TaskRunRepository taskRuns;
    private final GraphQLClient client;
    private final TimelineParser timelineParser;
    private final UserApi userApi;
    private final UserMediaTask userMediaTask;
    private final TagSearchTask tagSearchTask;
    private final ReplyTask replyTask;
    private final TextTask textTask;
    private final ProfileTask profileTask;
    private final TargetRepository targets;
    private final TargetListService targetListService;
    private final TargetListTask targetListTask;

    private final Path operationsOverride;

    private AppBootstrap(AppConfig config, Database database, Path operationsOverride) {
        this.config = config;
        this.database = database;
        this.operationsOverride = operationsOverride;
        this.records = new DownloadRecordRepository(database);
        this.cursors = new SyncCursorRepository(database);
        this.quotaRepository = new QuotaLedgerRepository(database);
        this.accountPool = AccountPool.from(config, Instant.now());
        this.rateLimiter = new RateLimiter(config.download().maxConcurrent(),
                config.rateLimit().requestIntervalMs());
        this.quotaLedger = new QuotaLedger(quotaRepository, config.quota());
        this.namingTemplate = new NamingTemplate(config.download().namingTemplate());
        this.taskRuns = new TaskRunRepository(database);
        OperationsConfig ops = OperationsConfig.load(operationsOverride);
        this.client = new GraphQLClient(config, ops, this.accountPool, this.rateLimiter, this.quotaLedger);
        this.userApi = new UserApi(this.client, 500);
        this.timelineParser = new TimelineParser(config.download().hasVideo(),
                config.download().hasRetweet());
        MediaDownloader mediaDownloader = new MediaDownloader(this.client.httpClient(),
                config.download(), this.namingTemplate);
        this.userMediaTask = new UserMediaTask(config, this.accountPool, userApi, timelineParser,
                mediaDownloader, this.records, this.cursors, this.taskRuns, this.quotaLedger);
        this.tagSearchTask = new TagSearchTask(config, this.accountPool, userApi, timelineParser,
                mediaDownloader, this.records, this.cursors, this.taskRuns, this.quotaLedger);
        this.replyTask = new ReplyTask(config, this.accountPool, userApi, timelineParser,
                mediaDownloader, this.records, this.cursors, this.taskRuns, this.quotaLedger);
        this.textTask = new TextTask(config, this.accountPool, userApi, timelineParser,
                mediaDownloader, this.records, this.cursors, this.taskRuns, this.quotaLedger);
        this.profileTask = new ProfileTask(config, this.accountPool, userApi, this.client.httpClient(),
                this.records, this.taskRuns, this.quotaLedger);
        this.targets = new TargetRepository(database);
        this.targetListService = new TargetListService(this.targets, this.accountPool, this.userApi);
        // 按 ID 列表顺序批量下载：每个条目走与单用户任务完全相同的路径
        this.targetListTask = new TargetListTask(this.targets, (screenName, options) ->
                this.userMediaTask.run(screenName, new AbstractTimelineTask.Options(
                        options.limitTweets(), options.dryRun(), options.fresh(), null)));
        this.quotaLedger.loadInto(this.accountPool);
    }

    public static AppBootstrap create(AppConfig config) {
        return create(config, null);
    }

    public static AppBootstrap create(AppConfig config, Path operationsOverride) {
        Database db = Database.open(config.dbPath());
        AppBootstrap boot = new AppBootstrap(config, db, operationsOverride);
        if (config.proxy().enabled() && config.proxy().mode() == AppConfig.ProxyMode.POOL) {
            log.warn("proxy.mode=pool 为预留能力，第一阶段仅使用列表首个：{}", config.proxy().firstOrNull());
        }
        return boot;
    }

    public AppConfig config() {
        return config;
    }

    public Database database() {
        return database;
    }

    public DownloadRecordRepository records() {
        return records;
    }

    public SyncCursorRepository cursors() {
        return cursors;
    }

    public QuotaLedgerRepository quotaRepository() {
        return quotaRepository;
    }

    public AccountPool accountPool() {
        return accountPool;
    }

    public RateLimiter rateLimiter() {
        return rateLimiter;
    }

    public QuotaLedger quotaLedger() {
        return quotaLedger;
    }

    public NamingTemplate namingTemplate() {
        return namingTemplate;
    }

    public TaskRunRepository taskRuns() {
        return taskRuns;
    }

    public GraphQLClient client() {
        return client;
    }

    public UserMediaTask userMediaTask() {
        return userMediaTask;
    }

    public TagSearchTask tagSearchTask() {
        return tagSearchTask;
    }

    public ReplyTask replyTask() {
        return replyTask;
    }

    public TextTask textTask() {
        return textTask;
    }

    public ProfileTask profileTask() {
        return profileTask;
    }

    public TargetRepository targets() {
        return targets;
    }

    public TargetListService targetListService() {
        return targetListService;
    }

    public TargetListTask targetListTask() {
        return targetListTask;
    }

    public UserApi userApi() {
        return userApi;
    }

    @Override
    public void close() {
        client.close();
        database.close();
    }
}
