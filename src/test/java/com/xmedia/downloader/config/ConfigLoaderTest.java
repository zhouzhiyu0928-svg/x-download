package com.xmedia.downloader.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xmedia.downloader.util.NamingTemplate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 配置加载：默认值、优先级、聚合报错、各校验分支。 */
class ConfigLoaderTest {

    @TempDir
    Path dir;

    private static final String VALID = """
            save-path: "./downloads"
            db-path: "./data/xmedia.db"
            log:
              level: DEBUG
              file: "./logs/x.log"
              max-history: 3
            accounts:
              - name: account1
                note: "本人账号"
                cookie: "auth_token=aaa; ct0=bbb;"
            proxy:
              mode: single
              list:
                - "http://127.0.0.1:10808"
            download:
              max-concurrent: 4
              has-video: true
              naming-template: "{user}/{date}_{tweetId}_{mediaKey}.{ext}"
              enable-incremental: true
              video-variant: highest
              resume-enabled: true
            rate-limit:
              request-interval-ms: 300
              max-retries: 2
            quota:
              per-account-daily: 800
              reserve-for-retry: 40
              estimate-tweets-per-call: 19
            """;

    private Path write(String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    private final ConfigLoader loader = new ConfigLoader();

    @Test
    @DisplayName("合法配置全量解析（含 H2/S2 新增字段）")
    void parsesValidConfig() throws IOException {
        AppConfig c = loader.load(write("ok.yaml", VALID).toString());

        assertEquals("./downloads", c.savePath());
        assertEquals("./data/xmedia.db", c.dbPath());
        assertEquals(new AppConfig.LogConfig("DEBUG", "./logs/x.log", 3), c.log());
        assertEquals(1, c.accounts().size());
        assertEquals("本人账号", c.accounts().get(0).note());
        assertEquals(AppConfig.ProxyMode.SINGLE, c.proxy().mode());
        assertEquals("http://127.0.0.1:10808", c.proxy().firstOrNull());
        assertEquals(4, c.download().maxConcurrent());
        assertEquals(AppConfig.VideoVariant.HIGHEST, c.download().videoVariant());
        assertTrue(c.download().resumeEnabled());
        assertEquals(300, c.rateLimit().requestIntervalMs());
        assertEquals(800, c.quota().perAccountDaily());
        assertEquals(19, c.quota().estimateTweetsPerCall());
        assertTrue(c.download().timeRange().endMillis() > c.download().timeRange().startMillis());
    }

    @Test
    @DisplayName("优先级：CLI ＞ 环境变量 ＞ 文件 ＞ 默认值")
    void precedenceCliOverEnvOverFile() throws IOException {
        Path p = write("p.yaml", VALID);
        Map<String, String> env = Map.of(
                "XMEDIA_MAX_CONCURRENT", "16",
                "XMEDIA_LOG_LEVEL", "WARN",
                "XMEDIA_SAVE_PATH", "./from-env");
        Map<String, String> cli = Map.of("download.max-concurrent", "32");

        AppConfig c = loader.load(p.toString(), env, cli);

        assertEquals(32, c.download().maxConcurrent(), "CLI 应压过环境变量");
        assertEquals("WARN", c.log().level(), "环境变量应压过文件");
        assertEquals("./from-env", c.savePath(), "环境变量应压过文件");
        assertNotEquals("./downloads", c.savePath(), "文件里的 save-path 应已被环境变量覆盖");
    }

    @Test
    @DisplayName("默认值：仅给 accounts 也能跑（其余取内置默认）")
    void defaultsApplied() throws IOException {
        Path p = write("min.yaml", """
                accounts:
                  - name: a1
                    cookie: "auth_token=x; ct0=y;"
                """);
        AppConfig c = loader.load(p.toString(), Map.of(), Map.of());
        assertEquals(AppConfig.DEFAULT_SAVE_PATH, c.savePath());
        assertEquals(AppConfig.DEFAULT_DB_PATH, c.dbPath());
        assertEquals(AppConfig.DEFAULT_MAX_CONCURRENT, c.download().maxConcurrent());
        assertEquals(NamingTemplate.DEFAULT_TEMPLATE, c.download().namingTemplate());
        assertEquals("INFO", c.log().level());
    }

    @Test
    @DisplayName("配置文件不存在 → 提示路径与用法")
    void missingFile() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> loader.load(dir.resolve("nope.yaml").toString()));
        assertTrue(e.getMessage().contains("nope.yaml"), e.getMessage());
        assertTrue(e.getMessage().contains("config.example.yaml"), e.getMessage());
    }

    @Test
    @DisplayName("多问题一次性报出（不是改一个报一个）")
    void aggregatesAllErrors() throws IOException {
        Path p = write("bad.yaml", """
                accounts:
                  - name: a1
                    cookie: "auth_token=x;"
                download:
                  max-concurrent: 0
                  likes: true
                  has-retweet: true
                quota:
                  per-account-daily: 10
                  reserve-for-retry: 50
                """);
        ConfigException e = assertThrows(ConfigException.class, () -> loader.load(p.toString(), Map.of(), Map.of()));
        String msg = e.getMessage();
        assertTrue(msg.contains("ct0"), msg);
        assertTrue(msg.contains("max-concurrent"), msg);
        assertTrue(msg.contains("likes"), msg);
        assertTrue(msg.contains("reserve-for-retry"), msg);
        assertTrue(msg.matches("(?s).*共 [2-9][0-9]* 项.*"), "应聚合多项：" + msg);
    }

    @Test
    @DisplayName("accounts 为空 / 账号名重复 都被拦")
    void accountsValidation() throws IOException {
        assertTrue(assertThrows(ConfigException.class,
                () -> loader.load(write("e1.yaml", "save-path: ./d\n").toString(), Map.of(), Map.of()))
                .getMessage().contains("accounts"));

        Path dup = write("dup.yaml", """
                accounts:
                  - name: same
                    cookie: "auth_token=x; ct0=y;"
                  - name: same
                    cookie: "auth_token=x; ct0=y;"
                """);
        assertTrue(assertThrows(ConfigException.class, () -> loader.load(dup.toString(), Map.of(), Map.of()))
                .getMessage().contains("重复"));
    }

    @Test
    @DisplayName("命名模板非法 → 启动即失败")
    void invalidNamingTemplate() throws IOException {
        Path p = write("tpl.yaml", """
                accounts:
                  - name: a1
                    cookie: "auth_token=x; ct0=y;"
                download:
                  naming-template: "{user}/{nosuchvar}.{ext}"
                """);
        assertTrue(assertThrows(ConfigException.class, () -> loader.load(p.toString(), Map.of(), Map.of()))
                .getMessage().contains("nosuchvar"));
    }

    @Test
    @DisplayName("代理：pool 结构可用；single 多地址被拦")
    void proxyValidation() throws IOException {
        Path pool = write("pool.yaml", """
                accounts:
                  - name: a1
                    cookie: "auth_token=x; ct0=y;"
                proxy:
                  mode: pool
                  list: ["http://127.0.0.1:10809", "http://127.0.0.1:10810"]
                """);
        AppConfig c = loader.load(pool.toString(), Map.of(), Map.of());
        assertEquals(AppConfig.ProxyMode.POOL, c.proxy().mode());
        assertEquals(2, c.proxy().list().size());

        Path bad = write("bad-proxy.yaml", """
                accounts:
                  - name: a1
                    cookie: "auth_token=x; ct0=y;"
                proxy:
                  mode: single
                  list: ["http://a:1", "http://b:2"]
                """);
        assertTrue(assertThrows(ConfigException.class, () -> loader.load(bad.toString(), Map.of(), Map.of()))
                .getMessage().contains("single"));
    }

    @Test
    @DisplayName("环境变量可注入代理列表（逗号分隔）")
    void envProxyList() throws IOException {
        Path p = write("env.yaml", """
                accounts:
                  - name: a1
                    cookie: "auth_token=x; ct0=y;"
                """);
        AppConfig c = loader.load(p.toString(),
                Map.of("XMEDIA_PROXY_LIST", "http://127.0.0.1:10808,http://127.0.0.1:10809"), Map.of());
        assertEquals(2, c.proxy().list().size());
        assertEquals(AppConfig.ProxyMode.POOL, c.proxy().mode(), "未显式指定 mode 时按条数推断为 pool");
        assertFalse(c.proxy().list().get(0).isBlank());

        // 单条 → 推断为 single
        AppConfig single = loader.load(p.toString(),
                Map.of("XMEDIA_PROXY_LIST", "http://127.0.0.1:10808"), Map.of());
        assertEquals(AppConfig.ProxyMode.SINGLE, single.proxy().mode());
    }

    @Test
    @DisplayName("likes 与亮点/转推互斥；likes 需标注本人账号")
    void likesRules() throws IOException {
        Path p = write("likes.yaml", """
                accounts:
                  - name: a1
                    cookie: "auth_token=x; ct0=y;"
                download:
                  likes: true
                  has-highlights: true
                """);
        String msg = assertThrows(ConfigException.class, () -> loader.load(p.toString(), Map.of(), Map.of()))
                .getMessage();
        assertTrue(msg.contains("has-highlights"), msg);
        assertTrue(msg.contains("note"), msg);
    }
}
