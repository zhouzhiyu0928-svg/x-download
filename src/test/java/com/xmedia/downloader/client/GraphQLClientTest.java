package com.xmedia.downloader.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.xmedia.downloader.account.Account;
import com.xmedia.downloader.account.AccountPool;
import com.xmedia.downloader.account.AccountStatus;
import com.xmedia.downloader.account.CookieSet;
import com.xmedia.downloader.config.AppConfig;
import com.xmedia.downloader.error.AuthException;
import com.xmedia.downloader.error.NetworkException;
import com.xmedia.downloader.error.OperationInvalidException;
import com.xmedia.downloader.error.RateLimitException;
import com.xmedia.downloader.ratelimit.QuotaLedger;
import com.xmedia.downloader.ratelimit.RateLimiter;
import com.xmedia.downloader.storage.Database;
import com.xmedia.downloader.storage.QuotaLedgerRepository;
import com.xmedia.downloader.support.TestConfigs;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** GraphQL 客户端：请求头完整性、错误分类（认证/限流/操作失效/网络）、重试、配额记账。 */
class GraphQLClientTest {

    @TempDir
    Path dir;

    private MockWebServer server;
    private Database db;
    private QuotaLedger quota;
    private AccountPool pool;
    private Account account;
    private GraphQLClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        AppConfig config = TestConfigs.minimal(dir.resolve("gql.db").toString(), "./downloads", 4, 0, 1);
        db = Database.open(dir.resolve("gql.db").toString());
        quota = new QuotaLedger(new QuotaLedgerRepository(db), config.quota());
        pool = new AccountPool(List.of(new Account("a1", "", CookieSet.parse(TestConfigs.COOKIE), 100)));
        account = pool.accounts().get(0);
        OperationsConfig ops = OperationsConfig.load().withBaseUrl(server.url("").toString());
        client = new GraphQLClient(config, ops, pool, new RateLimiter(4, 0), quota);
    }

    @AfterEach
    void tearDown() throws IOException {
        client.close();
        db.close();
        server.shutdown();
    }

    /** 由测试调用后，再把返回节点用于断言。 */
    private JsonNode call() {
        return client.get(account, "UserByScreenName", Map.of("screen_name", "tester"), "tester");
    }

    @Test
    @DisplayName("成功请求：带上 cookie / x-csrf-token / bearer，并计入配额 1 次")
    void happyPathHeadersAndQuota() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"data\":{\"ok\":true}}"));

        JsonNode root = call();

        assertTrue(root.path("data").path("ok").asBoolean());
        RecordedRequest request = server.takeRequest();
        assertEquals(TestConfigs.COOKIE, request.getHeader("cookie"));
        assertEquals("test-ct0", request.getHeader("x-csrf-token"));
        assertTrue(request.getHeader("authorization").startsWith("Bearer "));
        assertTrue(request.getHeader("user-agent").contains("Mozilla"));
        assertTrue(request.getPath().contains("variables=") && request.getPath().contains("features="));
        assertTrue(request.getPath().contains("screen_name"), "动态变量应进入 variables");
        assertEquals(1, quota.usedToday(account), "每次请求记 1 次配额");
    }

    @Test
    @DisplayName("POST 型操作（SearchTimeline）：变量与 features 走 JSON body，GET 会 404")
    void postOperationUsesJsonBody() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"data\":{\"search_by_raw_query\":{}}}"));

        client.get(account, "SearchTimeline",
                Map.of("rawQuery", "from:Susu0526m", "count", 20, "product", "Latest"), null);

        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"variables\""), body);
        assertTrue(body.contains("from:Susu0526m"), body);
        assertTrue(body.contains("\"features\""), body);
        assertTrue(request.getPath().contains("/i/api/graphql/"), request.getPath());
        assertTrue(!request.getPath().contains("variables="), "POST 不应把变量放查询串：" + request.getPath());
    }

    @Test
    @DisplayName("401 → AuthException 且账号置为失效（不重试）")
    void unauthorizedMarksAccountInvalid() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{}"));

        assertThrows(AuthException.class, this::call);
        assertEquals(AccountStatus.INVALID, account.status());
        assertEquals(1, server.getRequestCount(), "认证失败不应重试");
    }

    @Test
    @DisplayName("429 → RateLimitException 且账号进入冷却（不重试）")
    void tooManyRequestsMarksRateLimited() {
        server.enqueue(new MockResponse().setResponseCode(429).setBody("{}"));

        assertThrows(RateLimitException.class, this::call);
        assertEquals(AccountStatus.RATE_LIMITED, account.status());
        assertFalse(account.usable(Instant.now()));
        assertEquals(1, server.getRequestCount());
    }

    @Test
    @DisplayName("HTTP 200 但 errors code=88 → 仍按限流处理")
    void graphQlRateLimitError() {
        server.enqueue(new MockResponse().setBody(
                "{\"errors\":[{\"message\":\"Rate limit exceeded\",\"code\":88}]}"));

        assertThrows(RateLimitException.class, this::call);
        assertEquals(AccountStatus.RATE_LIMITED, account.status());
    }

    @Test
    @DisplayName("HTTP 400 且像「操作失效」→ OperationInvalidException（不重试）")
    void operationInvalidOn400() {
        server.enqueue(new MockResponse().setResponseCode(400)
                .setBody("{\"errors\":[{\"message\":\"Unknown operation\"}]}"));

        assertThrows(OperationInvalidException.class, this::call);
        assertEquals(1, server.getRequestCount(), "操作失效不应重试");
    }

    @Test
    @DisplayName("5xx 先失败后成功 → 重试成功；全部失败 → NetworkException")
    void retriesThenSucceeds() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        server.enqueue(new MockResponse().setBody("{\"data\":{\"ok\":true}}"));

        assertTrue(call().path("data").path("ok").asBoolean());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    @DisplayName("全部 5xx → NetworkException（不冒充成功）")
    void allFailuresBecomeNetworkException() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        assertThrows(NetworkException.class, this::call);
    }
}
