package com.xmedia.downloader.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 配额配置：「取消对 X 的调用次数限制」= {@code quota.per-account-daily: 0}（也是默认值）。
 * 之前默认 200/1000 的硬上限会在大批量（ID 列表）下载时直接卡死。
 */
class ConfigLoaderQuotaTest {

    @TempDir
    Path dir;

    private Path write(String yaml) throws Exception {
        Path file = dir.resolve("c" + Math.abs(yaml.hashCode()) + ".yaml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        return file;
    }

    /** 真实签名：load(path, env, cliOverrides)。 */
    private static AppConfig load(Path file) {
        return new ConfigLoader().load(file.toString(), Map.of(), Map.of());
    }

    private static final String BASE = """
            save-path: ./downloads
            db-path: ./data/x.db
            accounts:
              - name: a1
                cookie: "auth_token=t; ct0=c;"
            """;

    @Test
    @DisplayName("不写 quota 段 → 默认不限制（0），而不是 200 次硬上限")
    void defaultUnlimited() throws Exception {
        AppConfig config = load(write(BASE));
        assertEquals(0, config.quota().perAccountDaily());
    }

    @Test
    @DisplayName("显式 0 合法（= 不限）；正数按配置生效")
    void zeroAndPositive() throws Exception {
        assertEquals(0, load(write(BASE + "quota:\n  per-account-daily: 0\n")).quota().perAccountDaily());
        assertEquals(500, load(write(BASE + "quota:\n  per-account-daily: 500\n")).quota().perAccountDaily());
    }

    @Test
    @DisplayName("负数报错（配置错误要当场说清，而不是静默当成不限）")
    void negativeRejected() throws Exception {
        ConfigException e = assertThrows(ConfigException.class,
                () -> load(write(BASE + "quota:\n  per-account-daily: -5\n")));
        assertTrue(e.getMessage().contains("per-account-daily"), e.getMessage());
    }

    @Test
    @DisplayName("reserve-for-retry 只在设置了上限时才需要小于上限（不限时不报错）")
    void reserveCheckSkippedWhenUnlimited() throws Exception {
        // 不限次数 + reserve 很大：不应报错
        load(write(BASE + "quota:\n  per-account-daily: 0\n  reserve-for-retry: 999\n"));

        // 设了上限时仍要求 reserve < 上限
        ConfigException e = assertThrows(ConfigException.class,
                () -> load(write(BASE + "quota:\n  per-account-daily: 100\n  reserve-for-retry: 100\n")));
        assertTrue(e.getMessage().contains("reserve-for-retry"), e.getMessage());
    }
}
