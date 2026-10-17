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
import java.util.stream.Stream;
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

/** 纯文本任务（F9）：只写文本、绝不下媒体；增量第二次运行不重复写。 */
class TextTaskTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CREATED_AT = "Thu Oct 15 14:35:54 +0000 2026";

    @TempDir
    Path dir;

    private MockWebServer server;
    private OkHttpClient http;
    private Database db;
    private TextTask task;
    private AppConfig config;
    private int mediaRequests;

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
                if (path.contains("UserTweets")) {
                    return json(tweetsPage());
                }
                if (path.startsWith("/media/")) {
                    mediaRequests++;      // 纯文本任务不应该打到这里
                    Buffer body = new Buffer();
                    body.write(new byte[64], 0, 64);
                    return new MockResponse().setBody(body);
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        server.start();

        config = TestConfigs.minimal(dir.resolve("text.db").toString(), dir.resolve("downloads").toString(),
                2, 0, 1);
        db = Database.open(config.dbPath());
        QuotaLedger quota = new QuotaLedger(new QuotaLedgerRepository(db), config.quota());
        AccountPool pool = AccountPool.from(config, java.time.Instant.now());
        OperationsConfig ops = OperationsConfig.load().withBaseUrl(server.url("").toString());
        http = new OkHttpClient();
        GraphQLClient client = new GraphQLClient(config, ops, pool, new RateLimiter(2, 0), quota);
        DownloadRecordRepository records = new DownloadRecordRepository(db);
        task = new TextTask(config, pool, new UserApi(client, 20),
                new TimelineParser(true, false),
                new MediaDownloader(http, config.download(), NamingTemplate.defaultTemplate()),
                records, new SyncCursorRepository(db), new TaskRunRepository(db), quota);
    }

    @AfterEach
    void tearDown() throws IOException {
        http.connectionPool().evictAll();
        http.dispatcher().executorService().shutdownNow();
        db.close();
        server.shutdown();
    }

    private MockResponse json(JsonNode body) {
        return new MockResponse().setBody(body.toPrettyString()).setHeader("content-type", "application/json");
    }

    private JsonNode userResponse() {
        ObjectNode legacy = MAPPER.createObjectNode();
        legacy.put("name", "Susu").put("statuses_count", 2).put("media_count", 2);
        ObjectNode result = MAPPER.createObjectNode();
        result.put("__typename", "User").put("rest_id", "1650579264494874624");
        result.set("legacy", legacy);
        ObjectNode user = MAPPER.createObjectNode();
        user.set("result", result);
        ObjectNode root = MAPPER.createObjectNode();
        root.set("user", user);
        return data(root);
    }

    /** 两条带图推文：文本任务只写文本，图片不下载。 */
    private JsonNode tweetsPage() {
        ArrayNode entries = MAPPER.createArrayNode();
        entries.add(tweetEntry("2110741460089983155", "first tweet"));
        entries.add(tweetEntry("2110601926371061997", "second tweet"));
        ObjectNode instruction = MAPPER.createObjectNode();
        instruction.put("type", "TimelineAddEntries");
        instruction.set("entries", entries);
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

    private ObjectNode tweetEntry(String tweetId, String text) {
        ArrayNode media = MAPPER.createArrayNode();
        ObjectNode m = MAPPER.createObjectNode();
        m.put("type", "photo").put("media_key", "3_" + tweetId)
                .put("media_url_https", server.url("/media/" + tweetId + ".jpg").toString());
        media.add(m);

        ObjectNode legacy = MAPPER.createObjectNode();
        legacy.put("id_str", tweetId).put("created_at", CREATED_AT).put("full_text", text);
        legacy.set("extended_entities", MAPPER.createObjectNode().set("media", media));

        ObjectNode result = MAPPER.createObjectNode();
        result.put("rest_id", tweetId);
        result.set("legacy", legacy);
        result.set("core", MAPPER.createObjectNode().set("user_results",
                MAPPER.createObjectNode().set("result", MAPPER.createObjectNode().put("rest_id", "1")
                        .set("legacy", MAPPER.createObjectNode().put("screen_name", "Susu0526m")))));

        ObjectNode entry = MAPPER.createObjectNode();
        entry.put("entryId", "tweet-" + tweetId);
        ObjectNode itemContent = MAPPER.createObjectNode();
        ObjectNode tweetResults = MAPPER.createObjectNode();
        tweetResults.set("result", result);
        itemContent.set("tweet_results", tweetResults);
        entry.set("content", itemContent);
        return entry;
    }

    private JsonNode data(ObjectNode payload) {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("data", payload);
        return root;
    }

    private List<Path> textFiles() throws IOException {
        Path textDir = Path.of(config.savePath()).resolve("Susu0526m").resolve("text");
        if (!Files.exists(textDir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(textDir)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }

    @Test
    @DisplayName("只写文本：2 条推文 → 2 个 txt，且一个媒体请求都没有")
    void writesTextOnly() throws Exception {
        TaskResult result = task.run("Susu0526m", AbstractTimelineTask.Options.of(0, false));

        assertEquals(TaskResult.Status.SUCCESS, result.status());
        assertEquals(2, result.successCount(), "两条推文应算两条成功");
        List<Path> files = textFiles();
        assertEquals(2, files.size(), "应生成 2 个文本文件：" + files);
        String content = Files.readString(files.get(0));
        assertTrue(content.contains("推文ID: "), content);
        assertTrue(content.contains("full_text") || content.contains("tweet"), content);
        assertEquals(0, mediaRequests, "纯文本任务不得请求图片 CDN");
    }

    @Test
    @DisplayName("dry-run：只列清单，不写任何文本文件、不写水位线")
    void dryRunWritesNothing() throws Exception {
        TaskResult result = task.run("Susu0526m", AbstractTimelineTask.Options.of(0, true));

        assertEquals(2, result.skippedCount(), "dry-run 应把 2 条文本计入「列出」");
        assertEquals(0, textFiles().size());
        assertEquals(0, mediaRequests);
        // dry-run 必须零状态：连任务行都不写（曾漏写一行 task_run，被 verify-r4.sh 抓到）
        long taskRunRows = db.read(conn -> {
            try (var st = conn.createStatement();
                 var rs = st.executeQuery("SELECT COUNT(*) FROM task_run")) {
                return rs.next() ? (long) rs.getInt(1) : 0L;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertEquals(0L, taskRunRows, "dry-run 不应写 task_run");
    }
}
