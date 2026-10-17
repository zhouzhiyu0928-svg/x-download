package com.xmedia.downloader.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * GraphQL 操作配置（S13）：queryId / features / 请求头模板全部外置，可热更新无需重新打包。
 *
 * <p>默认读 classpath 的 {@code operations.yaml}（由原 Python 版实测值抽取生成），
 * 也支持用外部文件覆盖（{@code --operations <file>}）。
 */
public final class OperationsConfig {

    /**
     * @param baseUrl          该操作自己的主机（实测 SearchTimeline/TweetDetail 走 x.com，其余走 twitter.com）
     * @param requiresTxId    是否需要 x-client-transaction-id 头（F14：仅部分 operation 受校验）
     */
    public record Operation(String name, String path, String variablesJson, String featuresJson,
                            String fieldTogglesJson, String baseUrl, boolean requiresTxId, String method) {

        /** 是否用 POST 提交（实测 SearchTimeline 只认 POST，GET 返回空体 404）。 */
        public boolean post() {
            return "post".equalsIgnoreCase(method);
        }

        /** 完整请求地址前缀（主机 + 路径）。 */
        public String url() {
            return baseUrl + path;
        }
    }

    private final String baseUrl;
    private final String bearer;
    private final String userAgent;
    private final Map<String, Operation> operations = new LinkedHashMap<>();

    private OperationsConfig(String baseUrl, String bearer, String userAgent,
                             Map<String, Operation> operations) {
        this.baseUrl = baseUrl;
        this.bearer = bearer;
        this.userAgent = userAgent;
        this.operations.putAll(operations);
    }

    public static OperationsConfig load() {
        try (InputStream in = OperationsConfig.class.getResourceAsStream("/operations.yaml")) {
            if (in == null) {
                throw new IllegalStateException("classpath 缺少 operations.yaml");
            }
            return parse(in);
        } catch (IOException e) {
            throw new IllegalStateException("读取 operations.yaml 失败：" + e.getMessage(), e);
        }
    }

    /** 外部文件覆盖（热更新 queryId）。 */
    public static OperationsConfig load(Path override) {
        if (override == null) {
            return load();
        }
        if (!Files.exists(override)) {
            throw new IllegalArgumentException("操作配置文件不存在：" + override.toAbsolutePath());
        }
        try (Reader reader = Files.newBufferedReader(override, StandardCharsets.UTF_8)) {
            return parse(reader);
        } catch (IOException e) {
            throw new IllegalStateException("读取操作配置失败：" + override + "（" + e.getMessage() + "）", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static OperationsConfig parse(Object source) {
        Object loaded = new Yaml().load(source instanceof InputStream in
                ? new java.io.InputStreamReader(in, StandardCharsets.UTF_8) : (Reader) source);
        Map<String, Object> root = (Map<String, Object>) loaded;
        String baseUrl = String.valueOf(root.getOrDefault("base-url", "https://twitter.com"));
        String bearer = (String) root.get("bearer");
        String ua = (String) root.get("user-agent");
        if (bearer == null || bearer.isBlank()) {
            throw new IllegalStateException("operations.yaml 缺少 bearer");
        }
        Map<String, Operation> ops = new LinkedHashMap<>();
        Map<String, Object> rawOps = (Map<String, Object>) root.getOrDefault("operations", Map.of());
        rawOps.forEach((name, value) -> {
            Map<String, Object> m = (Map<String, Object>) value;
            String host = m.get("base-url") == null ? baseUrl : String.valueOf(m.get("base-url"));
            Object txid = m.get("txid");
            ops.put(name, new Operation(name,
                    String.valueOf(m.get("path")),
                    text(m.get("variables"), "{}"),
                    text(m.get("features"), "{}"),
                    text(m.get("field-toggles"), ""),
                    host,
                    txid != null && Boolean.parseBoolean(txid.toString()),
                    String.valueOf(m.getOrDefault("method", "get"))));
        });
        return new OperationsConfig(baseUrl, bearer, ua == null ? "" : ua, ops);
    }

    /** YAML 里可能是 String（原始 JSON）或 Map（SnakeYAML 已解析）→ 统一成 JSON 文本。 */
    private static String text(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof String s) {
            return s;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("操作配置项不是合法 JSON：" + value, e);
        }
    }

    /** 测试/自建客户端用：把所有操作的主机换成指定值（例如指向 MockWebServer）。 */
    public OperationsConfig withBaseUrl(String newBaseUrl) {
        Map<String, Operation> rewritten = new LinkedHashMap<>();
        operations.forEach((name, op) -> rewritten.put(name, new Operation(op.name(), op.path(),
                op.variablesJson(), op.featuresJson(), op.fieldTogglesJson(), newBaseUrl, op.requiresTxId(),
                op.method())));
        return new OperationsConfig(newBaseUrl, bearer, userAgent, rewritten);
    }

    public Operation operation(String name) {
        Operation op = operations.get(name);
        if (op == null) {
            throw new IllegalArgumentException("未知操作：" + name + "（可用：" + operations.keySet() + "）");
        }
        return op;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String bearer() {
        return bearer;
    }

    public String userAgent() {
        return userAgent;
    }
}
