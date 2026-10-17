package com.xmedia.downloader.config;

import com.xmedia.downloader.util.NamingTemplate;
import com.xmedia.downloader.util.TimeRange;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;

/**
 * 配置加载：YAML 文件 + 环境变量（{@code XMEDIA_*}）+ CLI 覆盖。
 *
 * <p>优先级（设计文档 §4.1.1）：<b>CLI 参数 ＞ 环境变量 ＞ 配置文件 ＞ 内置默认值</b>。
 * 校验失败时把所有问题一次性抛出（{@link ConfigException}），避免「改一个报一个」。
 */
public final class ConfigLoader {

    public static final String ENV_PREFIX = "XMEDIA_";

    /** 环境变量后缀 → 配置键（点号形式）。仅暴露常用项，Cookie 只能来自配置文件。 */
    private static final Map<String, String> ENV_KEYS = Map.ofEntries(
            Map.entry("SAVE_PATH", "save-path"),
            Map.entry("DB_PATH", "db-path"),
            Map.entry("LOG_LEVEL", "log.level"),
            Map.entry("LOG_FILE", "log.file"),
            Map.entry("LOG_MAX_HISTORY", "log.max-history"),
            Map.entry("PROXY_MODE", "proxy.mode"),
            Map.entry("PROXY_LIST", "proxy.list"),
            Map.entry("MAX_CONCURRENT", "download.max-concurrent"),
            Map.entry("HAS_VIDEO", "download.has-video"),
            Map.entry("HAS_RETWEET", "download.has-retweet"),
            Map.entry("HAS_HIGHLIGHTS", "download.has-highlights"),
            Map.entry("LIKES", "download.likes"),
            Map.entry("TIME_RANGE", "download.time-range"),
            Map.entry("NAMING_TEMPLATE", "download.naming-template"),
            Map.entry("ENABLE_INCREMENTAL", "download.enable-incremental"),
            Map.entry("VIDEO_VARIANT", "download.video-variant"),
            Map.entry("RESUME_ENABLED", "download.resume-enabled"),
            Map.entry("REQUEST_INTERVAL_MS", "rate-limit.request-interval-ms"),
            Map.entry("MAX_RETRIES", "rate-limit.max-retries"),
            Map.entry("QUOTA_PER_ACCOUNT_DAILY", "quota.per-account-daily"),
            Map.entry("QUOTA_RESERVE_FOR_RETRY", "quota.reserve-for-retry"),
            Map.entry("QUOTA_ESTIMATE_TWEETS_PER_CALL", "quota.estimate-tweets-per-call"));

    private static final Set<String> LOG_LEVELS = Set.of("ERROR", "WARN", "INFO", "DEBUG");
    private static final int MAX_CONCURRENT_LIMIT = 64;

    public AppConfig load(String path) {
        return load(path, System.getenv(), Map.of());
    }

    /**
     * @param env          环境变量表（通常传 System.getenv()，便于测试注入）
     * @param cliOverrides CLI 覆盖项（点号键，如 {@code download.max-concurrent}）
     */
    public AppConfig load(String path, Map<String, String> env, Map<String, String> cliOverrides) {
        Map<String, Object> root = readYaml(path);
        applyFlat(root, fromEnv(env));
        applyFlat(root, cliOverrides == null ? Map.of() : cliOverrides);
        return build(root);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readYaml(String path) {
        Path file = Path.of(path).toAbsolutePath();
        if (!Files.exists(file)) {
            throw new ConfigException("配置文件不存在：" + file
                    + "\n  可用 -c/--config 指定路径；字段说明见 config.example.yaml");
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object loaded = new Yaml().load(reader);
            if (loaded == null) {
                return new LinkedHashMap<>();
            }
            if (!(loaded instanceof Map)) {
                throw new ConfigException("配置文件顶层必须是键值映射：" + file);
            }
            return new LinkedHashMap<>((Map<String, Object>) loaded);
        } catch (IOException e) {
            throw new ConfigException("配置文件读取失败：" + file + "（" + e.getMessage() + "）", e);
        } catch (RuntimeException e) {
            if (e instanceof ConfigException) {
                throw e;
            }
            throw new ConfigException("配置文件 YAML 解析失败：" + file + "（" + e.getMessage() + "）", e);
        }
    }

    private Map<String, String> fromEnv(Map<String, String> env) {
        Map<String, String> out = new LinkedHashMap<>();
        if (env == null) {
            return out;
        }
        ENV_KEYS.forEach((suffix, key) -> {
            String value = env.get(ENV_PREFIX + suffix);
            if (value != null && !value.isBlank()) {
                out.put(key, value);
            }
        });
        return out;
    }

    /** 把点号键写入嵌套 map（覆盖已有值）。 */
    @SuppressWarnings("unchecked")
    private void applyFlat(Map<String, Object> root, Map<String, String> flat) {
        for (Map.Entry<String, String> e : flat.entrySet()) {
            String[] parts = e.getKey().split("\\.");
            Map<String, Object> cur = root;
            for (int i = 0; i < parts.length - 1; i++) {
                Object next = cur.get(parts[i]);
                if (!(next instanceof Map)) {
                    next = new LinkedHashMap<String, Object>();
                    cur.put(parts[i], next);
                }
                cur = (Map<String, Object>) next;
            }
            String last = parts[parts.length - 1];
            if (last.equals("list") && e.getValue() != null && parts[0].equals("proxy")) {
                List<String> items = new ArrayList<>();
                for (String s : e.getValue().split(",")) {
                    if (!s.isBlank()) {
                        items.add(s.trim());
                    }
                }
                cur.put(last, items);
            } else {
                cur.put(last, e.getValue());
            }
        }
    }

    private AppConfig build(Map<String, Object> root) {
        String savePath = str(root, "save-path", AppConfig.DEFAULT_SAVE_PATH);
        String dbPath = str(root, "db-path", AppConfig.DEFAULT_DB_PATH);
        AppConfig.LogConfig log = readLog(root);
        List<AppConfig.AccountConfig> accounts = readAccounts(root);
        AppConfig.ProxyConfig proxy = readProxy(root);
        AppConfig.DownloadConfig download = readDownload(root);
        AppConfig.RateLimitConfig rateLimit = readRateLimit(root);
        AppConfig.QuotaConfig quota = readQuota(root);
        AppConfig.TasksConfig tasks = readTasks(root);

        AppConfig config = new AppConfig(savePath, dbPath, log,
                List.copyOf(accounts), proxy, download, rateLimit, quota, tasks);
        validate(config);
        return config;
    }

    @SuppressWarnings("unchecked")
    private AppConfig.LogConfig readLog(Map<String, Object> root) {
        Map<String, Object> log = nested(root, "log");
        Object raw = log.get("max-history");
        int history = raw == null ? AppConfig.LogConfig.DEFAULT.maxHistory() : asInt(raw, "log.max-history");
        return new AppConfig.LogConfig(
                str(log, "level", AppConfig.LogConfig.DEFAULT.level()).toUpperCase(Locale.ROOT),
                str(log, "file", AppConfig.LogConfig.DEFAULT.file()),
                history);
    }

    @SuppressWarnings("unchecked")
    private List<AppConfig.AccountConfig> readAccounts(Map<String, Object> root) {
        Object raw = root.get("accounts");
        List<AppConfig.AccountConfig> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        if (!(raw instanceof List)) {
            throw new ConfigException("accounts 必须是列表（每个元素含 name / cookie）");
        }
        for (Object item : (List<Object>) raw) {
            if (!(item instanceof Map)) {
                throw new ConfigException("accounts 的元素必须是映射：name / note / cookie");
            }
            Map<String, Object> m = (Map<String, Object>) item;
            out.add(new AppConfig.AccountConfig(
                    str(m, "name", ""), str(m, "note", ""), str(m, "cookie", "")));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private AppConfig.ProxyConfig readProxy(Map<String, Object> root) {
        Map<String, Object> proxy = nested(root, "proxy");
        if (proxy.isEmpty()) {
            return AppConfig.ProxyConfig.NONE;
        }
        boolean modeExplicit = proxy.get("mode") != null;
        List<String> list = new ArrayList<>();
        Object raw = proxy.get("list");
        if (raw instanceof List) {
            for (Object o : (List<Object>) raw) {
                if (o != null && !o.toString().isBlank()) {
                    list.add(o.toString().trim());
                }
            }
        } else if (raw instanceof String s && !s.isBlank()) {
            for (String part : s.split(",")) {
                if (!part.isBlank()) {
                    list.add(part.trim());
                }
            }
        }
        AppConfig.ProxyMode mode;
        if (modeExplicit) {
            String modeText = str(proxy, "mode", "single");
            try {
                mode = AppConfig.ProxyMode.parse(modeText);
            } catch (IllegalArgumentException e) {
                throw new ConfigException("proxy.mode 只支持 single / pool，实际：" + modeText);
            }
        } else {
            // 未显式指定时按条数推断：多地址视为池（否则 env 只给 PROXY_LIST 会莫名报错）
            mode = list.size() > 1 ? AppConfig.ProxyMode.POOL : AppConfig.ProxyMode.SINGLE;
        }
        return new AppConfig.ProxyConfig(mode, List.copyOf(list));
    }

    @SuppressWarnings("unchecked")
    private AppConfig.DownloadConfig readDownload(Map<String, Object> root) {
        Map<String, Object> d = nested(root, "download");
        String naming = str(d, "naming-template", NamingTemplate.DEFAULT_TEMPLATE);
        String timeRangeText = str(d, "time-range", "");
        String variantText = str(d, "video-variant", "highest");
        AppConfig.VideoVariant variant;
        try {
            variant = AppConfig.VideoVariant.parse(variantText);
        } catch (IllegalArgumentException e) {
            throw new ConfigException("download.video-variant 只支持 highest / all / lowest，实际：" + variantText);
        }
        TimeRange range;
        try {
            range = TimeRange.parse(timeRangeText);
        } catch (IllegalArgumentException e) {
            throw new ConfigException("download.time-range 非法：" + e.getMessage());
        }
        return new AppConfig.DownloadConfig(
                intVal(d, "max-concurrent", AppConfig.DEFAULT_MAX_CONCURRENT),
                boolVal(d, "has-video", true),
                boolVal(d, "has-retweet", false),
                boolVal(d, "has-highlights", false),
                boolVal(d, "likes", false),
                range,
                naming,
                str(d, "image-format", "orig").toLowerCase(Locale.ROOT),
                boolVal(d, "enable-incremental", true),
                variant,
                boolVal(d, "resume-enabled", true));
    }

    private AppConfig.RateLimitConfig readRateLimit(Map<String, Object> root) {
        Map<String, Object> r = nested(root, "rate-limit");
        return new AppConfig.RateLimitConfig(
                intVal(r, "request-interval-ms", 500),
                intVal(r, "max-retries", 3));
    }

    private AppConfig.QuotaConfig readQuota(Map<String, Object> root) {
        Map<String, Object> q = nested(root, "quota");
        return new AppConfig.QuotaConfig(
                // 0（或负）= 不限制每日调用次数（默认就不限制，按用户要求取消 200 次上限）
                intVal(q, "per-account-daily", 0),
                intVal(q, "reserve-for-retry", 50),
                intVal(q, "estimate-tweets-per-call", 19));
    }

    @SuppressWarnings("unchecked")
    private AppConfig.TasksConfig readTasks(Map<String, Object> root) {
        Map<String, Object> t = nested(root, "tasks");
        if (t.isEmpty()) {
            return AppConfig.TasksConfig.defaults();
        }
        Map<String, Object> tag = nested(t, "tag");
        Map<String, Object> reply = nested(t, "reply");
        Map<String, Object> text = nested(t, "text");
        Map<String, Object> profile = nested(t, "profile");
        AppConfig.TasksConfig d = AppConfig.TasksConfig.defaults();
        return new AppConfig.TasksConfig(
                new AppConfig.TasksConfig.TagConfig(
                        str(tag, "filter", d.tag().filter()),
                        str(tag, "product", d.tag().product()),
                        boolVal(tag, "save-text", d.tag().saveText()),
                        intVal(tag, "page-size", d.tag().pageSize())),
                new AppConfig.TasksConfig.ReplyConfig(
                        boolVal(reply, "include-video", d.reply().includeVideo()),
                        intVal(reply, "page-size", d.reply().pageSize())),
                new AppConfig.TasksConfig.TextConfig(intVal(text, "page-size", d.text().pageSize())),
                new AppConfig.TasksConfig.ProfileConfig(
                        boolVal(profile, "download-avatar", d.profile().downloadAvatar()),
                        boolVal(profile, "download-banner", d.profile().downloadBanner()),
                        boolVal(profile, "save-description", d.profile().saveDescription())));
    }

    /** 一次性收集全部问题再抛出，避免「改一个报一个」。 */
    private void validate(AppConfig c) {
        List<String> errors = new ArrayList<>();
        if (c.savePath() == null || c.savePath().isBlank()) {
            errors.add("save-path 不能为空（媒体保存目录）");
        }
        if (c.dbPath() == null || c.dbPath().isBlank()) {
            errors.add("db-path 不能为空（SQLite 数据库文件路径）");
        }
        if (!LOG_LEVELS.contains(c.log().level())) {
            errors.add("log.level 只支持 " + LOG_LEVELS + "，实际：" + c.log().level());
        }
        if (c.log().maxHistory() < 1) {
            errors.add("log.max-history 必须 ≥ 1，实际：" + c.log().maxHistory());
        }
        if (c.accounts().isEmpty()) {
            errors.add("accounts 至少需要一个账号（name + cookie）");
        }
        Set<String> names = new java.util.LinkedHashSet<>();
        for (AppConfig.AccountConfig a : c.accounts()) {
            if (a.name() == null || a.name().isBlank()) {
                errors.add("accounts[].name 不能为空");
                continue;
            }
            if (!names.add(a.name())) {
                errors.add("账号名重复：" + a.name());
            }
            String cookie = a.cookie() == null ? "" : a.cookie();
            List<String> missing = new ArrayList<>();
            if (!cookie.contains("auth_token=")) {
                missing.add("auth_token");
            }
            if (!cookie.contains("ct0=")) {
                missing.add("ct0");
            }
            if (!missing.isEmpty()) {
                errors.add("账号 " + a.name() + " 的 cookie 缺少字段：" + String.join("、", missing)
                        + "（浏览器登录态里复制，注意保留分号）");
            }
        }
        if (c.proxy().enabled() && c.proxy().mode() == AppConfig.ProxyMode.SINGLE
                && c.proxy().list().size() > 1) {
            errors.add("proxy.mode=single 只允许一个代理地址，实际 " + c.proxy().list().size() + " 个"
                    + "（多代理请用 mode: pool）");
        }
        int mc = c.download().maxConcurrent();
        if (mc < 1 || mc > MAX_CONCURRENT_LIMIT) {
            errors.add("download.max-concurrent 必须在 1.." + MAX_CONCURRENT_LIMIT + "，实际：" + mc);
        }
        if (!Set.of("orig", "4096x4096", "jpg", "png").contains(c.download().imageFormat())) {
            errors.add("download.image-format 只支持 orig / 4096x4096 / jpg / png，实际："
                    + c.download().imageFormat());
        }
        if (c.download().likes() && c.download().hasHighlights()) {
            errors.add("download.likes 与 has-highlights 不能同时为 true（可见性模式互斥）");
        }
        if (c.download().likes() && c.download().hasRetweet()) {
            errors.add("download.likes 与 has-retweet 不能同时为 true（Likes 时间线不含转推语义）");
        }
        if (c.download().likes()) {
            boolean anySelf = c.accounts().stream().anyMatch(a -> a.note() != null && !a.note().isBlank());
            if (!anySelf) {
                errors.add("启用 download.likes 时，需在 accounts[].note 标明本人账号（Likes 只能查该账号自身）");
            }
        }
        try {
            NamingTemplate.validate(c.download().namingTemplate());
        } catch (IllegalArgumentException e) {
            errors.add("download.naming-template 非法：" + e.getMessage());
        }
        if (c.rateLimit().requestIntervalMs() < 0) {
            errors.add("rate-limit.request-interval-ms 不能为负，实际：" + c.rateLimit().requestIntervalMs());
        }
        int retries = c.rateLimit().maxRetries();
        if (retries < 0 || retries > 100) {
            errors.add("rate-limit.max-retries 必须在 0..100，实际：" + retries);
        }
        AppConfig.QuotaConfig q = c.quota();
        if (q.perAccountDaily() < 0) {
            errors.add("quota.per-account-daily 不能为负（0 = 不限制），实际：" + q.perAccountDaily());
        }
        if (q.reserveForRetry() < 0) {
            errors.add("quota.reserve-for-retry 不能为负，实际：" + q.reserveForRetry());
        }
        if (q.perAccountDaily() > 0 && q.reserveForRetry() >= q.perAccountDaily()) {
            errors.add("quota.reserve-for-retry 必须小于 per-account-daily（实际 "
                    + q.reserveForRetry() + " >= " + q.perAccountDaily() + "）");
        }
        if (q.estimateTweetsPerCall() < 1) {
            errors.add("quota.estimate-tweets-per-call 必须 ≥ 1，实际：" + q.estimateTweetsPerCall());
        }
        AppConfig.TasksConfig tc = c.tasks();
        if (!Set.of("", "filter:images", "filter:videos", "filter:media", "filter:links",
                "filter:native_video").contains(tc.tag().filter())) {
            errors.add("tasks.tag.filter 只支持空 / filter:images / filter:videos / filter:media / filter:links / "
                    + "filter:native_video，实际：" + tc.tag().filter());
        }
        if (!Set.of("latest", "top").contains(tc.tag().product().toLowerCase(Locale.ROOT))) {
            errors.add("tasks.tag.product 只支持 Latest / Top，实际：" + tc.tag().product());
        }
        for (Map.Entry<String, Integer> e : Map.of("tasks.tag.page-size", tc.tag().pageSize(),
                "tasks.reply.page-size", tc.reply().pageSize(),
                "tasks.text.page-size", tc.text().pageSize()).entrySet()) {
            if (e.getValue() < 1 || e.getValue() > 100) {
                errors.add(e.getKey() + " 必须在 1..100，实际：" + e.getValue());
            }
        }

        if (!errors.isEmpty()) {
            StringBuilder sb = new StringBuilder("配置校验未通过，共 " + errors.size() + " 项：");
            for (String e : errors) {
                sb.append("\n  ✗ ").append(e);
            }
            throw new ConfigException(sb.toString());
        }
    }

    // ---------------------------------------------------------------- helpers
    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> root, String key) {
        Object v = root.get(key);
        if (v instanceof Map) {
            return (Map<String, Object>) v;
        }
        return new HashMap<>();
    }

    private static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : v.toString().trim();
    }

    private static int intVal(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new ConfigException("配置项 " + key + " 应为整数，实际：" + v);
        }
    }

    private static int asInt(Object v, String key) {
        return intVal(Map.of(key, v), key, 0);
    }

    private static boolean boolVal(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = v.toString().trim().toLowerCase(Locale.ROOT);
        if (s.equals("true") || s.equals("yes") || s.equals("1")) {
            return true;
        }
        if (s.equals("false") || s.equals("no") || s.equals("0")) {
            return false;
        }
        throw new ConfigException("配置项 " + key + " 应为布尔值，实际：" + v);
    }
}
