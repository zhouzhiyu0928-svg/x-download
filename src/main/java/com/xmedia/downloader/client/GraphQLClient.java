package com.xmedia.downloader.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xmedia.downloader.account.Account;
import com.xmedia.downloader.account.AccountPool;
import com.xmedia.downloader.config.AppConfig;
import com.xmedia.downloader.error.AuthException;
import com.xmedia.downloader.error.NetworkException;
import com.xmedia.downloader.error.OperationInvalidException;
import com.xmedia.downloader.error.RateLimitException;
import com.xmedia.downloader.ratelimit.Lease;
import com.xmedia.downloader.ratelimit.QuotaLedger;
import com.xmedia.downloader.ratelimit.RateLimiter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GraphQL 客户端：请求头组装、有限重试、错误分类、配额记账。
 *
 * <p>请求头（与原 Python 版实测一致，这两个端点**不需要** {@code x-client-transaction-id}）：
 * user-agent / authorization: Bearer / cookie / x-csrf-token(取自 ct0) / referer。
 *
 * <p>错误分类（MUST 分清，否则会误重试）：
 * <ul>
 *   <li>401/403、errors code 32/34/215 → {@link AuthException}（换号，不重试）</li>
 *   <li>429、errors code 88 → {@link RateLimitException}（退避；同时给账号打冷却）</li>
 *   <li>400 且响应像「操作失效」→ {@link OperationInvalidException}（提示更新 operations.yaml，不重试）</li>
 *   <li>5xx / IO 异常 → 有限重试，最后抛 {@link NetworkException}</li>
 * </ul>
 */
public final class GraphQLClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GraphQLClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OperationsConfig ops;
    private final OkHttpClient http;
    private final AccountPool pool;
    private final RateLimiter limiter;
    private final QuotaLedger quota;
    private final int maxRetries;
    private final TransactionIdProvider transactionIdProvider;

    /** TxId 生成器（F14）：当前默认不生成（实测 UserMedia/UserTweets/UserProfile 不需要，Tag/评论区同样可通）。 */
    public interface TransactionIdProvider {
        String transactionId(String method, String path);
    }

    public GraphQLClient(AppConfig config, OperationsConfig ops, AccountPool pool,
                         RateLimiter limiter, QuotaLedger quota) {
        this(config, ops, pool, limiter, quota, (method, path) -> null);
    }

    public GraphQLClient(AppConfig config, OperationsConfig ops, AccountPool pool,
                         RateLimiter limiter, QuotaLedger quota, TransactionIdProvider transactionIdProvider) {
        this.transactionIdProvider = transactionIdProvider;
        this.ops = ops;
        this.pool = pool;
        this.limiter = limiter;
        this.quota = quota;
        this.maxRetries = Math.max(0, config.rateLimit().maxRetries());
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(15))
                .readTimeout(Duration.ofSeconds(60))
                .callTimeout(Duration.ofSeconds(120))
                .retryOnConnectionFailure(true);
        Proxy proxy = buildProxy(config.proxy().firstOrNull());
        if (proxy != null) {
            builder.proxy(proxy);
            log.info("使用代理：{}", config.proxy().firstOrNull());
        }
        this.http = builder.build();
    }

    /** 支持 http(s):// 与 socks5:// 两种代理写法（本机 Clash 常两种都开）。 */
    public static Proxy buildProxy(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String lower = url.toLowerCase();
        boolean socks = lower.startsWith("socks");
        String stripped = url.replaceFirst("^[a-zA-Z0-9]+://", "");
        String hostPort = stripped.contains("/") ? stripped.substring(0, stripped.indexOf('/')) : stripped;
        String[] parts = hostPort.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("代理地址应为 host:port 形式：" + url);
        }
        return new Proxy(socks ? Proxy.Type.SOCKS : Proxy.Type.HTTP,
                new InetSocketAddress(parts[0], Integer.parseInt(parts[1])));
    }

    /**
     * 发起一次 GraphQL GET。
     *
     * @param account           使用的账号（决定 cookie / csrf）
     * @param operationName     operations.yaml 中的操作名
     * @param dynamicVariables  动态变量（userId / cursor / screen_name 等），覆盖配置默认值
     * @param refererScreenName Referer 里的账号名，可空
     */
    public JsonNode get(Account account, String operationName, Map<String, Object> dynamicVariables,
                        String refererScreenName) {
        OperationsConfig.Operation op = ops.operation(operationName);
        ObjectNode variables = variablesNode(op, dynamicVariables);
        String url = op.post() ? op.url() : buildUrl(op, variables);

        IOException lastError = null;
        String lastDetail = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                long backoff = 500L * (1L << (attempt - 1));
                log.warn("重试 {}/{}：{}（等待 {}ms）", attempt, maxRetries, operationName, backoff);
                sleep(backoff);
            }
            try (Lease lease = Lease.open(limiter, pool, account)) {
                Request.Builder requestBuilder = new Request.Builder()
                        .url(url)
                        .header("user-agent", ops.userAgent())
                        .header("authorization", "Bearer " + ops.bearer())
                        .header("x-csrf-token", account.cookie().csrfToken())
                        .header("cookie", account.cookie().rawForHeader())
                        .header("accept", "*/*")
                        .header("accept-language", "zh-CN,zh;q=0.9,en;q=0.8")
                        .header("referer", "https://twitter.com/" + (refererScreenName == null ? "" : refererScreenName));
                if (op.post()) {
                    ObjectNode body = MAPPER.createObjectNode();
                    body.set("variables", variables);
                    body.set("features", MAPPER.readTree(op.featuresJson()));
                    if (op.fieldTogglesJson() != null && !op.fieldTogglesJson().isBlank()) {
                        body.set("fieldToggles", MAPPER.readTree(op.fieldTogglesJson()));
                    }
                    requestBuilder.header("content-type", "application/json")
                            .post(okhttp3.RequestBody.create(body.toString(),
                                    okhttp3.MediaType.parse("application/json")));
                } else {
                    requestBuilder.get();
                }
                // F14：只有受校验的 operation 才带 TxId；未接生成器时先不带（实测该头目前非强制）
                if (op.requiresTxId()) {
                    String txid = transactionIdProvider.transactionId("GET", op.path());
                    if (txid != null && !txid.isBlank()) {
                        requestBuilder.header("x-client-transaction-id", txid);
                    }
                }
                Request request = requestBuilder.build();

                try (Response response = http.newCall(request).execute()) {
                    quota.recordCall(account);   // 请求已发出即计入配额（与成败无关）
                    String body = response.body() == null ? "" : response.body().string();
                    int code = response.code();

                    if (code == 401 || code == 403) {
                        pool.markInvalid(account);
                        throw new AuthException(account.name(),
                                "HTTP " + code + "：Cookie 可能已失效，请重新从浏览器复制");
                    }
                    if (code == 429) {
                        limiter.onRateLimited(Duration.ofSeconds(30));
                        pool.markRateLimited(account, Duration.ofMinutes(15), java.time.Instant.now());
                        throw new RateLimitException("HTTP 429：触发频率限制，已退避 30s 并冷却该账号 15 分钟");
                    }
                    if (code == 400 && looksLikeOperationInvalid(body)) {
                        throw new OperationInvalidException(operationName,
                                "操作可能已失效（queryId 过期）。请更新 operations.yaml：\n  " + snippet(body));
                    }
                    if (!response.isSuccessful()) {
                        lastDetail = "HTTP " + code + " " + snippet(body);
                        log.warn("{} 返回 {}", operationName, lastDetail);
                        continue;   // 5xx 等走重试
                    }

                    JsonNode root = MAPPER.readTree(body);
                    classifyGraphQlErrors(account, operationName, root);
                    return root;
                }
            } catch (RateLimitException | AuthException | OperationInvalidException e) {
                throw e;                       // 分类错误直接上抛，不浪费重试
            } catch (IOException e) {
                lastError = e;
                lastDetail = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.warn("{} 网络异常：{}", operationName, lastDetail);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NetworkException("请求被中断", e);
            }
        }
        throw new NetworkException("请求失败（已重试 " + maxRetries + " 次）：" + lastDetail,
                lastError == null ? new IOException(lastDetail) : lastError);
    }

    /** 合并配置默认变量与调用方动态变量。 */
    private ObjectNode variablesNode(OperationsConfig.Operation op, Map<String, Object> dynamicVariables) {
        ObjectNode variables;
        try {
            variables = (ObjectNode) MAPPER.readTree(op.variablesJson());
        } catch (IOException e) {
            throw new IllegalStateException("operations.yaml 的 variables 不是合法 JSON", e);
        }
        if (dynamicVariables != null) {
            dynamicVariables.forEach((k, v) -> {
                if (v == null) {
                    variables.remove(k);
                } else if (v instanceof Integer i) {
                    variables.put(k, i);
                } else if (v instanceof Boolean b) {
                    variables.put(k, b);
                } else {
                    variables.put(k, v.toString());
                }
            });
        }
        return variables;
    }

    /** GET 形式：变量与 features 拼在查询串上。 */
    private String buildUrl(OperationsConfig.Operation op, ObjectNode variables) {
        HttpUrl base = HttpUrl.parse(op.url());
        if (base == null) {
            throw new IllegalStateException("非法的接口地址：" + op.url());
        }
        HttpUrl.Builder b = base.newBuilder()
                .addQueryParameter("variables", variables.toString())
                .addQueryParameter("features", op.featuresJson());
        if (op.fieldTogglesJson() != null && !op.fieldTogglesJson().isBlank()) {
            b.addQueryParameter("fieldToggles", op.fieldTogglesJson());
        }
        return b.build().toString();
    }

    /** 200 但 body 里带 errors：多数是限流/认证，个别是操作问题。 */
    private void classifyGraphQlErrors(Account account, String operationName, JsonNode root) {
        JsonNode errors = root.path("errors");
        if (!errors.isArray() || errors.isEmpty()) {
            return;
        }
        for (JsonNode err : errors) {
            int code = err.path("code").asInt(-1);
            String message = err.path("message").asText("");
            if (code == 88 || message.toLowerCase().contains("rate limit")) {
                pool.markRateLimited(account, Duration.ofMinutes(15), java.time.Instant.now());
                throw new RateLimitException("GraphQL 限流（code 88）：" + message);
            }
            if (code == 32 || code == 34 || code == 215 || code == 353
                    || message.toLowerCase().contains("could not authenticate")) {
                pool.markInvalid(account);
                throw new AuthException(account.name(), "GraphQL 认证失败（code " + code + "）：" + message);
            }
            log.warn("{} 返回 errors：code={} message={}", operationName, code, message);
        }
        if (root.path("data").isMissingNode() || root.path("data").isNull()) {
            throw new OperationInvalidException(operationName,
                    "响应无 data 字段，可能操作已失效：" + snippet(root.toString()));
        }
    }

    private static boolean looksLikeOperationInvalid(String body) {
        String b = body.toLowerCase();
        return b.contains("unknown operation") || b.contains("query: unspecified")
                || (b.contains("operationname") && b.contains("not found"));
    }

    private static String snippet(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }

    private static void sleep(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 供下载器复用同一套代理/超时配置（图片 CDN 走同一出口）。 */
    public OkHttpClient httpClient() {
        return http;
    }

    /** 便于测试与诊断：把当前账号与操作拼出的 URL 打出来（不含 cookie）。 */
    public String describeUrl(String operationName, Map<String, Object> dynamicVariables) {
        OperationsConfig.Operation op = ops.operation(operationName);
        ObjectNode variables = variablesNode(op, dynamicVariables);
        return op.post() ? op.url() + " [POST]" : buildUrl(op, variables);
    }

    @Override
    public void close() {
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }
}
