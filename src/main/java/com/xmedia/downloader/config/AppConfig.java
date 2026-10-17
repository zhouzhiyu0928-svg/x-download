package com.xmedia.downloader.config;

import com.xmedia.downloader.util.TimeRange;
import java.util.List;
import java.util.Locale;

/**
 * 强类型配置对象（《详细设计》V1.1 §4.1.1 全量字段）。
 *
 * <p>含整改要点：{@code db-path}（S2）、{@code log.*}（S2）、{@code proxy.mode}（S1/Q6）、
 * {@code quota.*}（H2）、{@code naming-template} 稳定键（S9）、{@code resume-enabled}（S4）、
 * {@code accounts[].note} 本人账号标记（H6）。
 */
public record AppConfig(
        String savePath,
        String dbPath,
        LogConfig log,
        List<AccountConfig> accounts,
        ProxyConfig proxy,
        DownloadConfig download,
        RateLimitConfig rateLimit,
        QuotaConfig quota,
        TasksConfig tasks) {

    public static final String DEFAULT_SAVE_PATH = "./downloads";
    public static final String DEFAULT_DB_PATH = "./data/xmedia.db";
    public static final int DEFAULT_MAX_CONCURRENT = 8;

    public record LogConfig(String level, String file, int maxHistory) {
        public static final LogConfig DEFAULT = new LogConfig("INFO", "./logs/xmedia.log", 7);
    }

    /** @param note 账号备注；标记「本人账号」的账号必须用于 likes / 亮点 任务（H6） */
    public record AccountConfig(String name, String note, String cookie) {
    }

    public enum ProxyMode {
        SINGLE,
        POOL;

        public static ProxyMode parse(String text) {
            return valueOf(text.trim().toUpperCase(Locale.ROOT));
        }
    }

    /** @param list 代理地址列表；第一阶段只实现 {@link ProxyMode#SINGLE}，pool 为预留（Q6） */
    public record ProxyConfig(ProxyMode mode, List<String> list) {
        public static final ProxyConfig NONE = new ProxyConfig(ProxyMode.SINGLE, List.of());

        public boolean enabled() {
            return list != null && !list.isEmpty();
        }

        public String firstOrNull() {
            return enabled() ? list.get(0) : null;
        }
    }

    public enum VideoVariant {
        HIGHEST,
        ALL,
        LOWEST;

        public static VideoVariant parse(String text) {
            return valueOf(text.trim().toUpperCase(Locale.ROOT));
        }
    }

    public record DownloadConfig(
            int maxConcurrent,
            boolean hasVideo,
            boolean hasRetweet,
            boolean hasHighlights,
            boolean likes,
            TimeRange timeRange,
            String namingTemplate,
            // 图片取图方式：orig（原图，默认）/ 4096x4096 / jpg / png —— 对齐原版 settings.json 的 image_format
            String imageFormat,
            boolean enableIncremental,
            VideoVariant videoVariant,
            boolean resumeEnabled) {
    }

    public record RateLimitConfig(int requestIntervalMs, int maxRetries) {
    }

    /** 各任务类型的专属参数（F7/F8/F9/F10）。 */
    public record TasksConfig(TagConfig tag, ReplyConfig reply, TextConfig text, ProfileConfig profile) {

        /** @param filter  高级搜索过滤：空 / filter:images / filter:videos / filter:media / filter:links / filter:native_video */
        public record TagConfig(String filter, String product, boolean saveText, int pageSize) {
        }

        public record ReplyConfig(boolean includeVideo, int pageSize) {
        }

        public record TextConfig(int pageSize) {
        }

        public record ProfileConfig(boolean downloadAvatar, boolean downloadBanner, boolean saveDescription) {
        }

        public static TasksConfig defaults() {
            return new TasksConfig(new TagConfig("", "Latest", false, 20),
                    new ReplyConfig(true, 20), new TextConfig(20),
                    new ProfileConfig(true, true, true));
        }
    }

    /** @param estimateTweetsPerCall 启动前估算用：调用次数 ≈ 推文数 / 该值（H2，实测约 19） */
    public record QuotaConfig(int perAccountDaily, int reserveForRetry, int estimateTweetsPerCall) {
    }
}
