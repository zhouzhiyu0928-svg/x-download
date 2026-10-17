package com.xmedia.downloader.client;

import com.xmedia.downloader.account.Account;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * queryId 自更新（应对需求文档 §7 的头号风险「X GraphQL 接口发生变更」）。
 *
 * <p>原理：X 的经典 Web 客户端把「操作名 → queryId」表打进 {@code main.<hash>.js}；
 * 用账号 Cookie 取 {@code https://x.com/home}，找到该 bundle 并解析
 * {@code queryId:"…",operationName:"…"} 对，然后按操作名回写 operations.yaml（改前自动备份）。
 *
 * <p>这样 queryId 过期时用户执行一条命令即可修好，不必改代码重新打包（S13 的设计意图）。
 */
public final class QueryIdRefresher {

    private static final Logger log = LoggerFactory.getLogger(QueryIdRefresher.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final Pattern BUNDLE = Pattern.compile(
            "https://abs\\.twimg\\.com/responsive-web/client-web/main\\.[0-9a-f]+\\.js");
    private static final Pattern PAIR = Pattern.compile(
            "queryId:\"([0-9A-Za-z_-]{15,})\",operationName:\"([A-Za-z_]+)\"");
    private static final Pattern PATH_QID = Pattern.compile("(path: /i/api/graphql/)([0-9A-Za-z_-]+)");

    public record Result(Path file, Path backup, Map<String, String> updated, Set<String> notFound) {
    }

    private QueryIdRefresher() {
    }

    /** @param operationsFile 要回写的 operations.yaml（原文件会先备份） */
    public static Result refresh(OkHttpClient http, Account account, Path operationsFile, String userAgent)
            throws IOException {
        return refresh(http, account, operationsFile, userAgent, true);
    }

    /**
     * @param write false = 只解析不落盘（dry-run：先看会改哪些）
     */
    public static Result refresh(OkHttpClient http, Account account, Path operationsFile, String userAgent,
                                boolean write) throws IOException {
        if (!Files.exists(operationsFile)) {
            throw new IllegalArgumentException("操作配置文件不存在：" + operationsFile.toAbsolutePath());
        }
        Map<String, String> fresh = fetchOperationIds(http, account, userAgent);
        log.info("从客户端 bundle 解析到 {} 个操作", fresh.size());

        String text = Files.readString(operationsFile, StandardCharsets.UTF_8);
        ApplyResult applied = applyQids(text, fresh);
        text = applied.text();
        Map<String, String> updated = applied.updated();
        Set<String> notFound = applied.notFound();

        Path backup = null;
        if (!updated.isEmpty() && write) {
            backup = operationsFile.resolveSibling(operationsFile.getFileName() + ".bak-"
                    + STAMP.format(Instant.now().atZone(ZoneId.systemDefault())));
            Files.copy(operationsFile, backup);
            Files.writeString(operationsFile, text, StandardCharsets.UTF_8);
        }
        return new Result(operationsFile, backup, updated, notFound);
    }

    /** 解析 bundle 里的「操作名 → queryId」表。 */
    public static Map<String, String> fetchOperationIds(OkHttpClient http, Account account, String userAgent)
            throws IOException {
        String home = get(http, account, userAgent, "https://x.com/home");
        Matcher bundle = BUNDLE.matcher(home);
        if (!bundle.find()) {
            throw new IOException("未能在首页找到经典客户端 bundle（可能是登录态失效或前端已改版）");
        }
        String js = get(http, account, userAgent, bundle.group());
        Map<String, String> map = parseOperationIds(js);
        if (map.isEmpty()) {
            throw new IOException("bundle 中未解析到 queryId/operationName 对");
        }
        return map;
    }

    /** 回写结果：新文本 + 变更明细 + 未能在 bundle 中匹配到的操作。 */
    public record ApplyResult(String text, Map<String, String> updated, Set<String> notFound) {
    }

    /** 纯函数：按操作名把 bundle 里的最新 queryId 写进 operations.yaml 文本（便于测试）。 */
    public static ApplyResult applyQids(String text, Map<String, String> fresh) {
        Map<String, String> updated = new LinkedHashMap<>();
        Set<String> notFound = new LinkedHashSet<>();
        // 统一行尾：Windows 编辑器/patch 工具会把文件改成 CRLF，按行比较会全部失配
        String working = text.replace("\r\n", "\n").replace("\r", "\n");
        for (String op : operationsIn(text)) {
            // UserProfile 与 UserByScreenName 共用客户端同一个操作名
            String bundleKey = op.equals("UserProfile") ? "UserByScreenName" : op;
            String qid = fresh.get(bundleKey);
            String block = block(working, op);
            if (qid == null || block == null) {
                notFound.add(op);
                continue;
            }
            String current = currentQid(block);
            if (qid.equals(current)) {
                continue;
            }
            working = working.replace(block, PATH_QID.matcher(block).replaceFirst("$1" + qid));
            updated.put(op, current + " -> " + qid);
        }
        return new ApplyResult(working, updated, notFound);
        // 注意：返回值统一为 LF，原文件的 CRLF 会被顺带规整（对 YAML 语义无影响）
    }

    /** 从 bundle 文本里解析「操作名 → queryId」（同名取首个，与客户端自身查找一致）。 */
    public static Map<String, String> parseOperationIds(String js) {
        Map<String, String> map = new LinkedHashMap<>();
        Matcher pair = PAIR.matcher(js);
        while (pair.find()) {
            map.putIfAbsent(pair.group(2), pair.group(1));
        }
        return map;
    }

    private static String get(OkHttpClient http, Account account, String userAgent, String url) throws IOException {
        Request.Builder rb = new Request.Builder().url(url).get();
        if (userAgent != null && !userAgent.isBlank()) {
            rb.header("user-agent", userAgent);
        }
        if (account != null) {
            rb.header("cookie", account.cookie().rawForHeader())
                    .header("x-csrf-token", account.cookie().csrfToken());
        }
        try (Response response = http.newCall(rb.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code() + " ← " + url);
            }
            return response.body().string();
        }
    }

    private static List<String> operationsIn(String text) {
        Matcher m = Pattern.compile("(?m)^  ([A-Za-z][A-Za-z0-9_]*):$").matcher(text);
        List<String> out = new java.util.ArrayList<>();
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static String block(String text, String op) {
        // 按行解析（正则 + DOTALL 处理缩进块容易踩坑）：从 "  OpName:" 起收集后续 4 空格缩进行
        String[] lines = text.split("\n");
        String header = "  " + op + ":";
        StringBuilder sb = new StringBuilder();
        boolean inBlock = false;
        for (String line : lines) {
            if (!inBlock && line.equals(header)) {
                inBlock = true;
                sb.append(line).append('\n');
                continue;
            }
            if (inBlock) {
                if (line.startsWith("    ")) {
                    sb.append(line).append('\n');
                } else {
                    break;
                }
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static String currentQid(String block) {
        Matcher m = PATH_QID.matcher(block);
        return m.find() ? m.group(2) : null;
    }
}
