package com.xmedia.downloader.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xmedia.downloader.account.AccountPool;
import com.xmedia.downloader.client.GraphQLClient;
import com.xmedia.downloader.client.OperationsConfig;
import com.xmedia.downloader.client.TimelineParser;
import com.xmedia.downloader.client.UserApi;
import com.xmedia.downloader.config.AppConfig;
import com.xmedia.downloader.downloader.MediaDownloader;
import com.xmedia.downloader.model.RecordStatus;
import com.xmedia.downloader.model.TaskResult;
import com.xmedia.downloader.ratelimit.QuotaLedger;
import com.xmedia.downloader.ratelimit.RateLimiter;
import com.xmedia.downloader.storage.Database;
import com.xmedia.downloader.storage.DownloadRecordRepository;
import com.xmedia.downloader.storage.QuotaLedgerRepository;
import com.xmedia.downloader.storage.SyncCursorRepository;
import com.xmedia.downloader.storage.TaskRunRepository;
import com.xmedia.downloader.support.TestConfigs;
import com.xmedia.downloader.util.NamingTemplate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 任务级集成测试（MockWebServer 冒充 X）：一页多推文都要下、二跑零重复、水位线提前终止。
 *
 * <p>这里守的是一条真实事故：曾把「本轮最新 ID」当成「初始水位线」用，导致同页第一条推文处理完后，
 * 同页更老的推文全被判为已处理而漏下（一页 4 条只下到 1 条）。
 */
class UserMediaTaskTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CREATED_AT = "Thu Oct 15 14:35:54 +0000 2026";

    @TempDir
    Path dir;

    private MockWebServer server;
    private final java.util.concurrent.atomic.AtomicInteger mediaCalls = new java.util.concurrent.atomic.AtomicInteger();
    private Database db;
    private OkHttpClient http;
    private UserMediaTask task;
    private DownloadRecordRepository records;
    private SyncCursorRepository cursors;
    private AppConfig config;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.contains("UserByScreenName")) {
                    return json(userResponse());
                }
                if (path.contains("UserMedia")) {
                    // 首次返回两条推文，之后返回空页（模拟真实分页到底）
                    return json(mediaCalls.incrementAndGet() == 1 ? mediaPage() : emptyPage());
                }
                if (path.startsWith("/media/")) {
                    Buffer body = new Buffer();
                    body.write(new byte[512], 0, 512);
                    return new MockResponse().setBody(body);
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        server.start();

        config = TestConfigs.minimal(dir.resolve("task.db").toString(), dir.resolve("downloads").toString(),
                2, 0, 1);
        db = Database.open(config.dbPath());
        records = new DownloadRecordRepository(db);
        cursors = new SyncCursorRepository(db);
        QuotaLedger quota = new QuotaLedger(new QuotaLedgerRepository(db), config.quota());
        AccountPool pool = AccountPool.from(config, java.time.Instant.now());
        OperationsConfig ops = OperationsConfig.load().withBaseUrl(server.url("").toString());
        http = new OkHttpClient();
        GraphQLClient client = new GraphQLClient(config, ops, pool, new RateLimiter(2, 0), quota);
        task = new UserMediaTask(config, pool, new UserApi(client, 500),
                new TimelineParser(true, false),
                new MediaDownloader(http, config.download(), NamingTemplate.defaultTemplate()),
                records, cursors, new TaskRunRepository(db), quota);
    }

    @AfterEach
    void tearDown() throws IOException {
        http.connectionPool().evictAll();
        http.dispatcher().executorService().shutdownNow();
        db.close();
        server.shutdown();
    }

    private MockResponse json(JsonNode body) {
        return new MockResponse().setBody(body.toPrettyString())
                .setHeader("content-type", "application/json");
    }

    /** UserByScreenName 响应。 */
    private JsonNode userResponse() {
        ObjectNode legacy = MAPPER.createObjectNode();
        legacy.put("name", "Susu").put("statuses_count", 2).put("media_count", 4);
        ObjectNode result = MAPPER.createObjectNode();
        result.put("__typename", "User").put("rest_id", "1650579264494874624");
        result.set("legacy", legacy);
        ObjectNode user = MAPPER.createObjectNode();
        user.set("result", result);
        ObjectNode root = MAPPER.createObjectNode();
        root.set("user", user);
        return data(root);
    }

    /** 一页两条推文，各 2 张图（媒体地址指向本 MockWebServer）。 */
    private JsonNode mediaPage() {
        ArrayNode entries = MAPPER.createArrayNode();
        entries.add(tweetEntry("tweet-A", "2110741460089983155", new String[]{"3_a1", "3_a2"}));
        entries.add(tweetEntry("tweet-B", "2110601926371061997", new String[]{"3_b1", "3_b2"}));
        entries.add(entry("cursor-bottom-1", "CURSOR_NEXT"));
        ObjectNode instruction = MAPPER.createObjectNode();
        instruction.put("type", "TimelineAddEntries");
        instruction.set("entries", entries);
        ArrayNode instructions = MAPPER.createArrayNode();
        instructions.add(instruction);
        instructions.add(MAPPER.createObjectNode().put("type", "TimelineTerminateTimeline"));

        ObjectNode timeline = MAPPER.createObjectNode();
        timeline.set("instructions", instructions);
        ObjectNode timelineV2 = MAPPER.createObjectNode();
        timelineV2.set("timeline", timeline);
        ObjectNode result = MAPPER.createObjectNode();
        result.set("timeline_v2", timelineV2);
        ObjectNode user = MAPPER.createObjectNode();
        user.set("result", result);
        ObjectNode root = MAPPER.createObjectNode();
        root.set("user", user);
        return data(root);
    }

    /** 空页（无推文、无游标）——真实分页到底的形态。 */
    private JsonNode emptyPage() {
        ObjectNode instruction = MAPPER.createObjectNode();
        instruction.put("type", "TimelineTerminateTimeline");
        ArrayNode instructions = MAPPER.createArrayNode();
        instructions.add(instruction);
        ObjectNode timeline = MAPPER.createObjectNode();
        timeline.set("instructions", instructions);
        ObjectNode timelineV2 = MAPPER.createObjectNode();
        timelineV2.set("timeline", timeline);
        ObjectNode result = MAPPER.createObjectNode();
        result.set("timeline_v2", timelineV2);
        ObjectNode user = MAPPER.createObjectNode();
        user.set("result", result);
        ObjectNode root = MAPPER.createObjectNode();
        root.set("user", user);
        return data(root);
    }

    private ObjectNode tweetEntry(String entryId, String tweetId, String[] mediaKeys) {
        ArrayNode media = MAPPER.createArrayNode();
        for (String key : mediaKeys) {
            ObjectNode m = MAPPER.createObjectNode();
            m.put("type", "photo").put("media_key", key)
                    .put("media_url_https", server.url("/media/" + key + ".jpg").toString());
            media.add(m);
        }
        ObjectNode legacy = MAPPER.createObjectNode();
        legacy.put("id_str", tweetId).put("created_at", CREATED_AT).put("full_text", "hi " + tweetId);
        legacy.set("extended_entities", MAPPER.createObjectNode().set("media", media));

        ObjectNode core = MAPPER.createObjectNode();
        ObjectNode userResult = MAPPER.createObjectNode();
        userResult.put("rest_id", "1650579264494874624");
        userResult.set("legacy", MAPPER.createObjectNode().put("screen_name", "Susu0526m"));
        core.set("user_results", MAPPER.createObjectNode().set("result", userResult));

        ObjectNode result = MAPPER.createObjectNode();
        result.put("rest_id", tweetId);
        result.set("legacy", legacy);
        result.set("core", core);

        ObjectNode tweetResults = MAPPER.createObjectNode();
        tweetResults.set("result", result);
        ObjectNode itemContent = MAPPER.createObjectNode();
        itemContent.set("tweet_results", tweetResults);
        ObjectNode entry = MAPPER.createObjectNode();
        entry.put("entryId", entryId);
        entry.set("content", itemContent);
        return entry;
    }

    private ObjectNode entry(String entryId, String cursor) {
        ObjectNode e = MAPPER.createObjectNode();
        e.put("entryId", entryId);
        e.set("content", MAPPER.createObjectNode().put("value", cursor));
        return e;
    }

    private JsonNode data(ObjectNode payload) {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("data", payload);
        return root;
    }

    private long downloadedFiles() throws IOException {
        Path root = Path.of(config.savePath());
        if (!Files.exists(root)) {
            return 0;
        }
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    @Test
    @DisplayName("一页两条推文：4 张图全下（回归：同页老推文不得被水位线滤掉）")
    void downloadsAllTweetsOnSamePage() throws IOException {
        TaskResult result = task.run("Susu0526m", UserMediaTask.Options.of(0, false));

        assertEquals(TaskResult.Status.SUCCESS, result.status());
        assertEquals(4, result.successCount(), "同页两条推文共 4 张图都应下载");
        assertEquals(0, result.failedCount());
        assertEquals(4L, downloadedFiles());
        assertEquals(4L, records.count(RecordStatus.SUCCESS));
        assertEquals(0L, records.count(RecordStatus.FAILED), "本用例不应产生失败记录");
    }

    @Test
    @DisplayName("第二次运行：零下载（记录级去重 + 水位线提前终止），且不再新增文件")
    void secondRunIsNoop() throws IOException {
        task.run("Susu0526m", UserMediaTask.Options.of(0, false));
        long filesAfterFirst = downloadedFiles();

        TaskResult second = task.run("Susu0526m", UserMediaTask.Options.of(0, false));

        assertEquals(0, second.successCount(), "第二次不得重复下载");
        assertEquals(filesAfterFirst, downloadedFiles());
        assertTrue(second.apiCallCount() <= 2, "水位线提前终止应省掉后续翻页，实际调用 " + second.apiCallCount());
        assertEquals(4L, records.count(RecordStatus.SUCCESS), "记录数不得增长");
        // 水位线 = 该页最新推文 ID
        assertEquals("2110741460089983155", cursors.lastMaxTweetId("1650579264494874624"));
    }

    @Test
    @DisplayName("dry-run：只列清单，不落盘、不写水位线、不写记录")
    void dryRunTouchesNothing() throws IOException {
        TaskResult result = task.run("Susu0526m", UserMediaTask.Options.of(0, true));

        assertEquals(4, result.skippedCount(), "dry-run 应把 4 个媒体计入「列出」");
        assertEquals(0L, downloadedFiles(), "dry-run 不得下载文件");
        assertEquals(0L, records.count(RecordStatus.SUCCESS), "dry-run 不得写记录");
        assertTrue(cursors.find("1650579264494874624").isEmpty(), "dry-run 不得写水位线");
    }
}
