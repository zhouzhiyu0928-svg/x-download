package com.xmedia.downloader.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xmedia.downloader.account.AccountPool;
import com.xmedia.downloader.config.AppConfig;
import com.xmedia.downloader.model.User;
import com.xmedia.downloader.model.UserProfile;
import com.xmedia.downloader.ratelimit.QuotaLedger;
import com.xmedia.downloader.ratelimit.RateLimiter;
import com.xmedia.downloader.storage.Database;
import com.xmedia.downloader.storage.QuotaLedgerRepository;
import com.xmedia.downloader.support.TestConfigs;
import java.io.IOException;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 用户/主页字段映射：必须同时兼容**旧 schema**（legacy.*）与 **2026-10 起的新 schema**
 * （legacy 消失，字段搬到 core / avatar / banner / profile_bio / tweet_counts）。
 * 换新 queryId 后曾出现「昵称=用户名、推文数=0、媒体数=0」的静默退化，故此处双形状守护。
 */
class UserApiTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    java.nio.file.Path dir;

    private MockWebServer server;
    private OkHttpClient http;
    private Database db;
    private UserApi api;
    private AccountPool pool;
    private String body;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        AppConfig config = TestConfigs.minimal(dir.resolve("u.db").toString(),
                dir.resolve("downloads").toString(), 2, 0, 1);
        db = Database.open(config.dbPath());
        QuotaLedger quota = new QuotaLedger(new QuotaLedgerRepository(db), config.quota());
        pool = AccountPool.from(config, java.time.Instant.now());
        OperationsConfig ops = OperationsConfig.load().withBaseUrl(server.url("").toString());
        http = new OkHttpClient();
        GraphQLClient client = new GraphQLClient(config, ops, pool, new RateLimiter(2, 0), quota);
        api = new UserApi(client, 20);
    }

    @AfterEach
    void tearDown() throws IOException {
        http.connectionPool().evictAll();
        http.dispatcher().executorService().shutdownNow();
        db.close();
        server.shutdown();
    }

    private void enqueue(String json) {
        body = json;
        server.enqueue(new MockResponse().setBody(json).setHeader("content-type", "application/json"));
    }

    /** 新 schema（2026-10 实测结构，取自真实响应裁剪）。 */
    private String newSchemaUser() {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("__typename", "User").put("rest_id", "1650579264494874624");
        result.set("core", MAPPER.createObjectNode().put("name", "Susu").put("screen_name", "Susu0526m"));
        result.set("avatar", MAPPER.createObjectNode()
                .put("image_url", "https://pbs.twimg.com/profile_images/210/RVCb02BB_normal.jpg"));
        result.set("banner", MAPPER.createObjectNode()
                .put("image_url", "https://pbs.twimg.com/profile_banners/1650579264494874624/1790045446"));
        result.set("profile_bio", MAPPER.createObjectNode().put("description", "女m无主"));
        result.set("tweet_counts", MAPPER.createObjectNode().put("tweets", 4).put("media_tweets", 4));
        return wrap(result);
    }

    /** 旧 schema（legacy.*）。 */
    private String legacySchemaUser() {
        ObjectNode legacy = MAPPER.createObjectNode();
        legacy.put("name", "老昵称").put("screen_name", "olduser").put("description", "旧简介")
                .put("statuses_count", 9).put("media_count", 5)
                .put("profile_image_url_https", "https://pbs.twimg.com/profile_images/1/a_normal.png")
                .put("profile_banner_url", "https://pbs.twimg.com/profile_banners/1/2");
        ObjectNode result = MAPPER.createObjectNode();
        result.put("__typename", "User").put("rest_id", "42");
        result.set("legacy", legacy);
        return wrap(result);
    }

    private String wrap(ObjectNode result) {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("data", MAPPER.createObjectNode().set("user", MAPPER.createObjectNode().set("result", result)));
        return root.toPrettyString();
    }

    @Test
    @DisplayName("新 schema：昵称/推文数/媒体数从 core 与 tweet_counts 取")
    void resolveNewSchema() {
        enqueue(newSchemaUser());
        User user = api.resolve(pool.acquire(), "Susu0526m");
        assertEquals("Susu", user.name());
        assertEquals(4, user.statusesCount());
        assertEquals(4, user.mediaCount());
        assertEquals("1650579264494874624", user.restId());
    }

    @Test
    @DisplayName("旧 schema：仍走 legacy（向后兼容）")
    void resolveLegacySchema() throws IOException {
        enqueue(legacySchemaUser());
        User user = api.resolve(pool.acquire(), "olduser");
        assertEquals("老昵称", user.name());
        assertEquals(9, user.statusesCount());
        assertEquals(5, user.mediaCount());
    }

    @Test
    @DisplayName("主页新 schema：头像升级 400x400、无扩展名 banner 自动补 /1500x500、简介取 profile_bio")
    void profileNewSchema() {
        enqueue(newSchemaUser());
        UserProfile p = api.profile(pool.acquire(), "Susu0526m");
        assertTrue(p.avatarUrl().endsWith("_400x400.jpg"), p.avatarUrl());
        assertEquals("https://pbs.twimg.com/profile_banners/1650579264494874624/1790045446/1500x500", p.bannerUrl());
        assertEquals("女m无主", p.description());
        assertEquals(4, p.mediaCount());
        assertEquals("Susu", p.name());
    }

    @Test
    @DisplayName("主页旧 schema：无扩展名的 banner 同样补 /1500x500（不然拿不到图）")
    void profileLegacySchema() {
        enqueue(legacySchemaUser());
        UserProfile p = api.profile(pool.acquire(), "olduser");
        assertEquals("https://pbs.twimg.com/profile_banners/1/2/1500x500", p.bannerUrl());
        assertEquals("旧简介", p.description());
        assertEquals("老昵称", p.name());
    }

    @Test
    @DisplayName("banner 地址自带扩展名时保持原样（不画蛇添足）")
    void bannerWithExtensionUntouched() throws Exception {
        JsonNode root = MAPPER.readTree(newSchemaUser());
        ((ObjectNode) root.path("data").path("user").path("result").path("banner"))
                .put("image_url", "https://pbs.twimg.com/profile_banners/1/2/banner.jpg");
        enqueue(root.toPrettyString());
        assertEquals("https://pbs.twimg.com/profile_banners/1/2/banner.jpg",
                api.profile(pool.acquire(), "Susu0526m").bannerUrl());
    }

    @Test
    @DisplayName("无 banner 时为 null（不生成无效请求）")
    void noBanner() throws Exception {
        JsonNode root = MAPPER.readTree(newSchemaUser());
        ((ObjectNode) root.path("data").path("user").path("result")).remove("banner");
        enqueue(root.toPrettyString());
        assertNull(api.profile(pool.acquire(), "Susu0526m").bannerUrl());
        assertTrue(body.contains("profile_bio"));
    }
}
