package com.xmedia.downloader.support;

import com.xmedia.downloader.config.AppConfig;
import com.xmedia.downloader.util.NamingTemplate;
import com.xmedia.downloader.util.TimeRange;
import java.util.List;

/** 测试用最小配置（避免每个测试重复搭建 record 层级）。 */
public final class TestConfigs {

    public static final String COOKIE = "auth_token=test-token; ct0=test-ct0;";

    private TestConfigs() {
    }

    /** 复制一份配置但替换配额（用于「不限次数」等场景）。 */
    public static AppConfig withQuota(AppConfig base, int perAccountDaily, int reserveForRetry) {
        return new AppConfig(base.savePath(), base.dbPath(), base.log(), base.accounts(), base.proxy(),
                base.download(), base.rateLimit(),
                new AppConfig.QuotaConfig(perAccountDaily, reserveForRetry,
                        base.quota().estimateTweetsPerCall()),
                base.tasks());
    }

    public static AppConfig minimal(String dbPath) {
        return minimal(dbPath, "./downloads", 2, 0, 1);
    }

    public static AppConfig minimal(String dbPath, String savePath, int maxConcurrent, int intervalMs, int maxRetries) {
        return new AppConfig(savePath, dbPath,
                new AppConfig.LogConfig("INFO", "./logs/x.log", 7),
                List.of(new AppConfig.AccountConfig("a1", "", COOKIE)),
                AppConfig.ProxyConfig.NONE,
                new AppConfig.DownloadConfig(maxConcurrent, true, false, false, false,
                        TimeRange.UNLIMITED, NamingTemplate.DEFAULT_TEMPLATE, "orig",
                        true, AppConfig.VideoVariant.HIGHEST, true),
                new AppConfig.RateLimitConfig(intervalMs, maxRetries),
                new AppConfig.QuotaConfig(100, 5, 19),
                AppConfig.TasksConfig.defaults());
    }
}
