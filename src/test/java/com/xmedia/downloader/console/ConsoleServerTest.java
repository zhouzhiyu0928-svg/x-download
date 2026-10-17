package com.xmedia.downloader.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xmedia.downloader.config.AppConfig;
import com.xmedia.downloader.support.TestConfigs;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 操作台（F23）：静态页可达、接口可用、**不回显 Cookie**、错误请求有明确提示。 */
class ConsoleServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    private ConsoleServer server;
    private String base;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        AppConfig config = TestConfigs.minimal(dir.resolve("console.db").toString(),
                dir.resolve("downloads").toString(), 2, 0, 1);
        server = new ConsoleServer(config, null);
        int port = server.start(0);      // 0 = 随机空闲端口
        base = "http://127.0.0.1:" + port;
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("首页返回操作台页面：4 个页签、不含历史/记录页签、含免责声明")
    void indexPage() throws Exception {
        HttpResponse<String> r = get("/");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("操作台"));
        assertTrue(r.body().contains("data-view=\"download\""));
        assertTrue(r.body().contains("data-view=\"targets\""), "应有 ID 列表页签");
        assertEquals(4, r.body().split("data-view=", -1).length - 1, "恰好 4 个页签");
        assertFalse(r.body().contains("data-view=\"history\""), "历史任务页签已按需求移除");
        assertFalse(r.body().contains("data-view=\"records\""), "下载记录页签已按需求移除");
        assertTrue(r.body().contains("生成统计报告"), "报告入口移到设置页");
        assertTrue(r.body().contains("免责") || r.body().contains("仅供编程学习"));
    }

    @Test
    @DisplayName("状态接口：初始空闲、日志数组可用")
    void statusEndpoint() throws Exception {
        JsonNode body = MAPPER.readTree(get("/api/status").body());
        assertFalse(body.path("status").path("running").asBoolean(true));
        assertTrue(body.path("logs").isArray());
        assertEquals(0, body.path("status").path("success").asInt());
    }

    @Test
    @DisplayName("设置接口：返回关注项且不含 cookie")
    void settingsEndpoint() throws Exception {
        HttpResponse<String> r = get("/api/settings");
        assertEquals(200, r.statusCode());
        assertFalse(r.body().toLowerCase().contains("auth_token"), "不得回显 Cookie");
        JsonNode body = MAPPER.readTree(r.body());
        assertEquals(2, body.path("maxConcurrent").asInt());
        assertTrue(body.path("namingTemplate").asText().contains("{user}"));
        assertTrue(body.has("tag") && body.has("reply"));
    }

    @Test
    @DisplayName("账号接口：给 name/状态/配额，绝不给 Cookie")
    void accountsEndpoint() throws Exception {
        HttpResponse<String> r = get("/api/accounts");
        assertEquals(200, r.statusCode());
        assertFalse(r.body().contains("test-token"), "账号接口不得包含 Cookie 内容：" + r.body());
        assertFalse(r.body().contains("test-ct0"), "账号接口不得包含 ct0：" + r.body());
        JsonNode row = MAPPER.readTree(r.body()).path("rows").path(0);
        assertEquals("a1", row.path("name").asText());
        assertEquals(100, row.path("quotaLimit").asInt());
    }

    @Test
    @DisplayName("记录/历史接口：空库返回空数组（结构正确）")
    void recordsAndHistory() throws Exception {
        assertTrue(MAPPER.readTree(get("/api/records").body()).path("rows").isArray());
        assertTrue(MAPPER.readTree(get("/api/history").body()).path("rows").isArray());
    }

    @Test
    @DisplayName("执行接口：空目标 → 400 明确提示；非 POST → 405")
    void runValidation() throws Exception {
        HttpResponse<String> empty = post("/api/run", "{\"task\":\"user\",\"targets\":[]}");
        assertEquals(400, empty.statusCode());
        assertTrue(MAPPER.readTree(empty.body()).path("error").asText().contains("至少一个目标"));

        HttpResponse<String> wrongMethod = get("/api/run");
        assertEquals(405, wrongMethod.statusCode());
    }

    @Test
    @DisplayName("停止接口：空闲时也安全返回")
    void stopIsSafe() throws Exception {
        HttpResponse<String> r = post("/api/stop", "");
        assertEquals(200, r.statusCode());
        assertTrue(MAPPER.readTree(r.body()).path("stopped").asBoolean());
    }

    @Test
    @DisplayName("ID 列表接口：列表为空 → 增加 → 去重 → 删除 → 顺序")
    void targetsCrud() throws Exception {
        JsonNode empty = MAPPER.readTree(get("/api/targets").body());
        assertEquals(0, empty.path("count").asInt());
        assertEquals(0, empty.path("invalidCount").asInt());
        assertTrue(empty.path("rows").isArray());

        // 增加：多行文本 + 备注；非法行被单独列出而不是整批失败
        String addBody = "{\"text\":\"Susu0526m 本人\\n@another_id\\n非法 名字!\",\"note\":\"\"}";
        JsonNode added = MAPPER.readTree(post("/api/targets", addBody).body());
        assertEquals(2, added.path("added").size(), added.toString());
        assertEquals(1, added.path("invalidInput").size(), added.toString());
        assertEquals(2, added.path("count").asInt());

        // 重复加入 → skipped
        JsonNode again = MAPPER.readTree(post("/api/targets", "{\"text\":\"susU0526M\"}").body());
        assertEquals(0, again.path("added").size());
        assertEquals(1, again.path("skipped").size());
        assertEquals(2, again.path("count").asInt());

        // 空内容 → 400
        assertEquals(400, post("/api/targets", "{\"text\":\"\"}").statusCode());

        JsonNode rows = MAPPER.readTree(get("/api/targets").body());
        long firstId = rows.path("rows").path(0).path("id").asLong();
        long secondId = rows.path("rows").path(1).path("id").asLong();
        assertEquals("Susu0526m", rows.path("rows").path(0).path("screenName").asText());
        assertEquals("UNKNOWN", rows.path("rows").path(0).path("status").asText());
        assertEquals(2, rows.path("runnableCount").asInt(), "未校验的条目默认参与下载");

        // 顺序：下移第一条（id 较大的应排到最后）
        JsonNode moved = MAPPER.readTree(post("/api/targets/move",
                "{\"id\":" + firstId + ",\"delta\":1}").body());
        assertTrue(moved.path("moved").asBoolean());
        assertEquals(secondId, MAPPER.readTree(get("/api/targets").body())
                .path("rows").path(0).path("id").asLong());

        // 停用 → 不参与下载
        post("/api/targets/toggle", "{\"id\":" + secondId + ",\"enabled\":false}");
        assertEquals(1, MAPPER.readTree(get("/api/targets").body()).path("runnableCount").asInt());

        // 删除
        JsonNode removed = MAPPER.readTree(post("/api/targets/remove",
                "{\"ids\":[" + firstId + "," + secondId + "]}").body());
        assertEquals(2, removed.path("removed").asInt());
        assertEquals(0, MAPPER.readTree(get("/api/targets").body()).path("count").asInt());
    }

    @Test
    @DisplayName("ID 列表：删除无效（空列表时安全返回 0）")
    void removeInvalidSafe() throws Exception {
        JsonNode d = MAPPER.readTree(post("/api/targets/remove-invalid", "").body());
        assertEquals(0, d.path("removed").asInt());
    }

    @Test
    @DisplayName("按 ID 列表顺序下载：列表为空也能安全启动（不发起任何网络请求）")
    void runWithTargetList() throws Exception {
        HttpResponse<String> r = post("/api/run", "{\"task\":\"user\",\"useTargetList\":true,\"targets\":[]}");
        assertEquals(200, r.statusCode());
        assertTrue(MAPPER.readTree(r.body()).path("started").asBoolean());
        // 等后台线程收尾，避免影响后续用例
        for (int i = 0; i < 40; i++) {
            if (!MAPPER.readTree(get("/api/status").body()).path("status").path("running").asBoolean()) {
                break;
            }
            Thread.sleep(100);
        }
    }

    @Test
    @DisplayName("畸形请求体 → 400（可读原因），不是空体 500")
    void malformedJsonGets400() throws Exception {
        HttpResponse<String> r = post("/api/targets", "{这不是 JSON");
        assertEquals(400, r.statusCode());
        assertTrue(MAPPER.readTree(r.body()).path("error").asText().contains("合法 JSON"),
                r.body());
    }

    @Test
    @DisplayName("未知路径 404")
    void unknownPath() throws Exception {
        assertEquals(404, get("/nope.html").statusCode());
    }
}
