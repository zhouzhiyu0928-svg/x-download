package com.xmedia.downloader.console;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.xmedia.downloader.account.Account;
import com.xmedia.downloader.cli.AppBootstrap;
import com.xmedia.downloader.config.AppConfig;
import com.xmedia.downloader.model.Target;
import com.xmedia.downloader.model.TaskResult;
import com.xmedia.downloader.storage.TargetRepository;
import com.xmedia.downloader.report.ReportGenerator;
import com.xmedia.downloader.target.TargetListService;
import com.xmedia.downloader.task.AbstractTimelineTask;
import com.xmedia.downloader.task.ProfileTask;
import com.xmedia.downloader.task.TargetListTask;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本地图形操作台（F23）：JDK 内置 HttpServer + 静态页面 + JSON 接口，零额外依赖。
 *
 * <p>安全：仅绑定 127.0.0.1；接口**绝不返回 Cookie**（账号接口只给 name/note/状态/配额）；
 * 一次性任务（页面按钮 → 后台线程执行），停止采用中断语义（正在下载的文件靠 .part + 续传兜底）。
 */
public final class ConsoleServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConsoleServer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AppConfig config;
    private final Path operationsOverride;
    private final RunState state = new RunState();
    private HttpServer server;
    private volatile boolean stopping;

    public ConsoleServer(AppConfig config, Path operationsOverride) {
        this.config = config;
        this.operationsOverride = operationsOverride;
    }

    public RunState state() {
        return state;
    }

    public int start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::handleIndex);
        server.createContext("/api/status", ex -> guarded(ex, this::handleStatus));
        server.createContext("/api/run", ex -> guarded(ex, this::handleRun));
        server.createContext("/api/stop", ex -> guarded(ex, this::handleStop));
        server.createContext("/api/history", ex -> guarded(ex, this::handleHistory));
        server.createContext("/api/records", ex -> guarded(ex, this::handleRecords));
        server.createContext("/api/accounts", ex -> guarded(ex, this::handleAccounts));
        server.createContext("/api/settings", ex -> guarded(ex, this::handleSettings));
        server.createContext("/api/report", ex -> guarded(ex, this::handleReport));
        server.createContext("/api/targets", ex -> guarded(ex, this::handleTargets));
        server.createContext("/api/targets/remove", ex -> guarded(ex, this::handleTargetsRemove));
        server.createContext("/api/targets/remove-invalid", ex -> guarded(ex, this::handleTargetsRemoveInvalid));
        server.createContext("/api/targets/check", ex -> guarded(ex, this::handleTargetsCheck));
        server.createContext("/api/targets/move", ex -> guarded(ex, this::handleTargetsMove));
        server.createContext("/api/targets/toggle", ex -> guarded(ex, this::handleTargetsToggle));
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        attachLogAppender();
        int bound = server.getAddress().getPort();
        log.info("操作台已启动：http://127.0.0.1:{}", bound);
        return bound;
    }

    /** 把日志接到页面日志框。 */
    private void attachLogAppender() {
        if (org.slf4j.LoggerFactory.getILoggerFactory() instanceof ch.qos.logback.classic.LoggerContext ctx) {
            UiLogAppender appender = new UiLogAppender(state);
            appender.setContext(ctx);
            appender.setName("UI");
            appender.start();
            ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(appender);
        }
    }

    /**
     * 统一异常兜底：请求体不合法 → 400 且带可读原因；内部异常 → 500。
     *
     * <p>没有这层包装时，一个畸形的 JSON 请求体会让处理器抛异常，HttpServer 只回空体 500，
     * 页面上看到的是一片空白，无从排查。
     */
    private void guarded(HttpExchange exchange, HttpHandler handler) throws IOException {
        try {
            handler.handle(exchange);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("请求体不是合法 JSON：{}", e.getOriginalMessage());
            respondJson(exchange, 400, error("请求体不是合法 JSON：" + e.getOriginalMessage()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("请求被拒绝：{}", e.getMessage());
            respondJson(exchange, 400, error(e.getMessage() == null ? "请求无效" : e.getMessage()));
        } catch (IOException e) {
            throw e;                       // 连接层错误交给容器
        } catch (RuntimeException e) {
            log.warn("接口处理失败", e);
            respondJson(exchange, 500, error(e.getClass().getSimpleName() + "：" + e.getMessage()));
        }
    }

    // ------------------------------------------------------------ 静态页
    private void handleIndex(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!path.equals("/") && !path.equals("/index.html")) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        try (InputStream in = getClass().getResourceAsStream("/console/index.html")) {
            if (in == null) {
                respondText(exchange, 500, "缺少资源 /console/index.html");
                return;
            }
            byte[] body = in.readAllBytes();
            exchange.getResponseHeaders().add("content-type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    // ------------------------------------------------------------ 状态 / 日志
    private void handleStatus(HttpExchange exchange) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("status", MAPPER.valueToTree(state.snapshot()));
        int since = intParam(exchange, "since", 0);
        ArrayNode logs = root.putArray("logs");
        for (String line : state.logsSince(since)) {
            logs.add(line);
        }
        root.put("logBase", since == 0 ? 0 : since);
        respondJson(exchange, 200, root);
    }

    // ------------------------------------------------------------ 启停
    private void handleRun(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respondJson(exchange, 405, error("请用 POST"));
            return;
        }
        JsonNode body = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
        String task = body.path("task").asText("user");
        boolean useTargetList = body.path("useTargetList").asBoolean(false);
        List<String> targets = new ArrayList<>();
        for (JsonNode t : body.path("targets")) {
            if (!t.asText().isBlank()) {
                targets.add(t.asText().trim());
            }
        }
        if (targets.isEmpty() && !useTargetList) {
            respondJson(exchange, 400, error("请填写至少一个目标（用户名 / Tag / 推文链接），或改用 ID 列表"));
            return;
        }
        if (state.running()) {
            respondJson(exchange, 409, error("已有任务在执行，请先停止或等待完成"));
            return;
        }
        int limit = body.path("limit").asInt(0);
        boolean fresh = body.path("fresh").asBoolean(false);
        boolean dryRun = body.path("dryRun").asBoolean(false) || isDryRunForced();
        stopping = false;
        state.begin(useTargetList ? "user-list" : task, targets, dryRun);
        if (useTargetList) {
            state.log("排队：按 ID 列表顺序下载" + (dryRun ? "（dry-run）" : ""));
        } else {
            for (String t : targets) {
                state.log("排队：" + task + " → " + t + (dryRun ? "（dry-run）" : ""));
            }
        }
        Thread worker = new Thread(() -> {
            if (useTargetList) {
                executeList(limit, fresh, dryRun);
            } else {
                execute(task, targets, limit, fresh, dryRun);
            }
        }, "console-run");
        worker.setDaemon(true);
        state.worker(worker);
        worker.start();
        ObjectNode ok = MAPPER.createObjectNode();
        ok.put("started", true);
        ok.put("dryRun", dryRun);
        respondJson(exchange, 200, ok);
    }

    private boolean isDryRunForced() {
        return dryRunForced;
    }

    /** 由 ConsoleCommand 传入：--dry-run 时页面按钮也只列清单。 */
    public void setDryRunForced(boolean value) {
        this.dryRunForced = value;
    }

    private volatile boolean dryRunForced;

    private void handleStop(HttpExchange exchange) throws IOException {
        stopping = true;
        Thread worker = state.worker();
        if (worker != null && worker.isAlive()) {
            worker.interrupt();
            state.log("已请求停止（中断当前任务；未完成的文件会留 .part，下次可续传）");
        } else {
            state.log("当前没有正在执行的任务");
        }
        state.finish();
        ObjectNode ok = MAPPER.createObjectNode();
        ok.put("stopped", true);
        respondJson(exchange, 200, ok);
    }

    private void execute(String task, List<String> targets, int limit, boolean fresh, boolean dryRun) {
        try (AppBootstrap boot = AppBootstrap.create(config, operationsOverride)) {
            for (String target : targets) {
                if (stopping || Thread.currentThread().isInterrupted()) {
                    state.log("已停止，剩余目标未执行");
                    break;
                }
                state.currentTarget(target);
                state.log("开始：" + task + " → " + target);
                TaskResult result = switch (task) {
                    case "tag" -> boot.tagSearchTask().run(target,
                            new AbstractTimelineTask.Options(limit, dryRun, fresh, null));
                    case "reply" -> boot.replyTask().run(target,
                            new AbstractTimelineTask.Options(limit, dryRun, fresh, null));
                    case "text" -> boot.textTask().run(target,
                            new AbstractTimelineTask.Options(limit, dryRun, fresh, null));
                    case "profile" -> boot.profileTask().run(target, new ProfileTask.Options(dryRun));
                    default -> boot.userMediaTask().run(target,
                            new AbstractTimelineTask.Options(limit, dryRun, fresh, null));
                };
                state.addResult(result.status().name(), result.successCount(), result.failedCount(),
                        result.skippedCount(), result.apiCallCount(), result.elapsedMillis());
                state.log("完成：" + target + " → " + result.status());
            }
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                state.log("任务被中断（停止请求）");
            } else {
                log.warn("操作台任务失败", e);
                state.log("[ERROR] 任务失败：" + e.getMessage());
                state.fail(e.getClass().getSimpleName() + "：" + e.getMessage());
            }
        } finally {
            state.finish();
            state.log("任务结束");
        }
    }

    // ------------------------------------------------------------ 历史 / 记录 / 账号 / 设置
    private void handleHistory(HttpExchange exchange) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode rows = root.putArray("rows");
        for (Map<String, String> row : queryRows("SELECT id, task_type, target, status, success_count,"
                + " failed_count, skipped_count, api_call_count, duration_ms, started_at, finished_at, message"
                + " FROM task_run ORDER BY id DESC LIMIT 200")) {
            rows.add(MAPPER.valueToTree(row));
        }
        respondJson(exchange, 200, root);
    }

    private void handleRecords(HttpExchange exchange) throws IOException {
        int limit = Math.min(intParam(exchange, "limit", 200), 1000);
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode rows = root.putArray("rows");
        for (Map<String, String> row : queryRows("SELECT status, task_type, user_screen_name, tweet_id,"
                + " media_key, media_type, file_size, local_path, downloaded_at, error_message"
                + " FROM download_record ORDER BY id DESC LIMIT " + limit)) {
            rows.add(MAPPER.valueToTree(row));
        }
        root.put("count", rows.size());
        respondJson(exchange, 200, root);
    }

    private void handleAccounts(HttpExchange exchange) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode rows = root.putArray("rows");
        try (AppBootstrap boot = AppBootstrap.create(config, operationsOverride)) {
            for (Account a : boot.accountPool().accounts()) {
                ObjectNode node = MAPPER.createObjectNode();
                node.put("name", a.name());
                node.put("note", a.note());
                node.put("status", a.status().name());
                node.put("quotaUsed", a.quotaUsedToday());
                node.put("quotaLimit", a.quotaLimit());
                node.put("remaining", a.remainingQuota());
                // 注意：绝不返回 Cookie
                rows.add(node);
            }
        }
        respondJson(exchange, 200, root);
    }

    private void handleSettings(HttpExchange exchange) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("savePath", config.savePath());
        root.put("dbPath", config.dbPath());
        root.put("logFile", config.log().file());
        root.put("logLevel", config.log().level());
        root.put("maxConcurrent", config.download().maxConcurrent());
        root.put("requestIntervalMs", config.rateLimit().requestIntervalMs());
        root.put("maxRetries", config.rateLimit().maxRetries());
        root.put("namingTemplate", config.download().namingTemplate());
        root.put("imageFormat", config.download().imageFormat());
        root.put("videoVariant", config.download().videoVariant().name());
        root.put("incremental", config.download().enableIncremental());
        root.put("resume", config.download().resumeEnabled());
        root.put("hasVideo", config.download().hasVideo());
        root.put("hasRetweet", config.download().hasRetweet());
        root.put("timeRange", config.download().timeRange().toString());
        root.put("quotaPerAccountDaily", config.quota().perAccountDaily());
        ObjectNode proxy = root.putObject("proxy");
        proxy.put("mode", config.proxy().mode().name());
        proxy.put("enabled", config.proxy().enabled());
        proxy.put("endpoint", config.proxy().firstOrNull() == null ? "" : config.proxy().firstOrNull());
        ObjectNode tag = root.putObject("tag");
        tag.put("filter", config.tasks().tag().filter());
        tag.put("product", config.tasks().tag().product());
        tag.put("saveText", config.tasks().tag().saveText());
        ObjectNode reply = root.putObject("reply");
        reply.put("includeVideo", config.tasks().reply().includeVideo());
        respondJson(exchange, 200, root);
    }

    private void handleReport(HttpExchange exchange) throws IOException {
        try (AppBootstrap boot = AppBootstrap.create(config, operationsOverride)) {
            ReportGenerator.Report report = new ReportGenerator(boot.database())
                    .generate(Path.of("./reports"), Set.of("csv", "md"), 50);
            ObjectNode root = MAPPER.createObjectNode();
            root.put("recordCount", report.recordCount());
            root.put("taskRunCount", report.taskRunCount());
            root.put("csv", report.csv() == null ? "" : report.csv().toAbsolutePath().toString());
            root.put("markdown", report.markdown() == null ? "" : report.markdown().toAbsolutePath().toString());
            state.log("报告已生成：" + root.path("markdown").asText(""));
            respondJson(exchange, 200, root);
        }
    }


    // ------------------------------------------------------------ ID 列表
    private static ObjectNode targetNode(Target t) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", t.id());
        node.put("screenName", t.screenName());
        node.put("note", t.note());
        node.put("status", t.status().name());
        node.put("displayName", t.displayName());
        node.put("mediaCount", t.mediaCount());
        node.put("tweetCount", t.tweetCount());
        node.put("lastError", t.lastError());
        node.put("lastCheckedAt", t.lastCheckedAt() == null ? "" : t.lastCheckedAt());
        node.put("enabled", t.enabled());
        node.put("sortOrder", t.sortOrder());
        return node;
    }

    /** GET = 列表；POST = 新增（names 数组或 text 多行；支持「ID 备注」写法）。 */
    private void handleTargets(HttpExchange exchange) throws IOException {
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode rows = root.putArray("rows");
            try (AppBootstrap boot = AppBootstrap.create(config, operationsOverride)) {
                for (Target t : boot.targets().list()) {
                    rows.add(targetNode(t));
                }
                root.put("count", rows.size());
                root.put("invalidCount", boot.targets().invalidCount());
                root.put("runnableCount", boot.targets().runnable().size());
            }
            respondJson(exchange, 200, root);
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respondJson(exchange, 405, error("请用 GET 或 POST"));
            return;
        }
        JsonNode body = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
        List<String> raw = new ArrayList<>();
        for (JsonNode n : body.path("names")) {
            if (!n.asText().isBlank()) {
                raw.add(n.asText().trim());
            }
        }
        for (String line : body.path("text").asText("").split("[\\r\\n]+")) {
            if (!line.isBlank()) {
                raw.add(line.trim());
            }
        }
        if (raw.isEmpty()) {
            respondJson(exchange, 400, error("请填写要加入列表的 ID（每行一个）"));
            return;
        }
        String note = body.path("note").asText("");
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode added = root.putArray("added");
        ArrayNode skipped = root.putArray("skipped");
        ArrayNode bad = root.putArray("invalidInput");
        try (AppBootstrap boot = AppBootstrap.create(config, operationsOverride)) {
            for (String item : raw) {
                String name = item;
                String ownNote = note;
                int sp = item.indexOf(' ');
                if (sp > 0) {
                    name = item.substring(0, sp);
                    if (note.isBlank()) {
                        ownNote = item.substring(sp + 1).trim();
                    }
                }
                try {
                    TargetRepository.AddOutcome outcome = boot.targets().add(name, ownNote);
                    if (outcome.created()) {
                        added.add(targetNode(outcome.target()));
                        state.log("列表新增：" + outcome.target().label());
                    } else {
                        skipped.add(outcome.target().screenName());
                    }
                } catch (IllegalArgumentException e) {
                    bad.add(item);
                    state.log("[跳过] " + e.getMessage());
                }
            }
            root.put("count", boot.targets().count());
        }
        respondJson(exchange, 200, root);
    }

    private void handleTargetsRemove(HttpExchange exchange) throws IOException {
        JsonNode body = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
        List<Long> ids = new ArrayList<>();
        for (JsonNode n : body.path("ids")) {
            ids.add(n.asLong());
        }
        if (body.hasNonNull("id")) {
            ids.add(body.path("id").asLong());
        }
        if (ids.isEmpty()) {
            respondJson(exchange, 400, error("请提供要删除的 ids"));
            return;
        }
        ObjectNode root = MAPPER.createObjectNode();
        int removed = 0;
        try (AppBootstrap boot = AppBootstrap.create(config, operationsOverride)) {
            for (Long id : ids) {
                Target t = boot.targets().find(id);
                if (t != null) {
                    removed += boot.targets().remove(id);
                    state.log("列表删除：" + t.label());
                }
            }
            root.put("removed", removed);
            root.put("count", boot.targets().count());
        }
        respondJson(exchange, 200, root);
    }

    private void handleTargetsRemoveInvalid(HttpExchange exchange) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        try (AppBootstrap boot = AppBootstrap.create(config, operationsOverride)) {
            List<Target> invalid = boot.targets().invalid();
            for (Target t : invalid) {
                state.log("删除无效 ID：" + t.screenName() + " ← " + t.lastError());
            }
            root.put("removed", boot.targets().removeInvalid());
            root.put("count", boot.targets().count());
        }
        respondJson(exchange, 200, root);
    }

    /** 校验（后台线程跑，界面看进度与日志）。 */
    private void handleTargetsCheck(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respondJson(exchange, 405, error("请用 POST"));
            return;
        }
        if (state.running()) {
            respondJson(exchange, 409, error("已有任务在执行，请先停止或等待完成"));
            return;
        }
        JsonNode body = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
        boolean all = body.path("all").asBoolean(false);
        stopping = false;
        state.begin("check-targets", List.of(), false);
        state.log(all ? "开始校验 ID 列表（全部重新校验）" : "开始校验 ID 列表（只校验未校验 / 已判无效的）");
        Thread worker = new Thread(() -> executeCheck(!all), "console-check");
        worker.setDaemon(true);
        state.worker(worker);
        worker.start();
        ObjectNode ok = MAPPER.createObjectNode();
        ok.put("started", true);
        respondJson(exchange, 200, ok);
    }

    private void handleTargetsMove(HttpExchange exchange) throws IOException {
        JsonNode body = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
        long id = body.path("id").asLong();
        int delta = body.path("delta").asInt(-1) < 0 ? -1 : 1;
        ObjectNode root = MAPPER.createObjectNode();
        try (AppBootstrap boot = AppBootstrap.create(config, operationsOverride)) {
            root.put("moved", boot.targets().move(id, delta));
        }
        respondJson(exchange, 200, root);
    }

    private void handleTargetsToggle(HttpExchange exchange) throws IOException {
        JsonNode body = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
        long id = body.path("id").asLong();
        boolean enabled = body.path("enabled").asBoolean(true);
        try (AppBootstrap boot = AppBootstrap.create(config, operationsOverride)) {
            boot.targets().setEnabled(id, enabled);
            Target t = boot.targets().find(id);
            state.log((enabled ? "已启用：" : "已停用：") + (t == null ? id : t.label()));
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("ok", true);
        respondJson(exchange, 200, root);
    }

    /** 按 ID 列表顺序逐个下载。 */
    private void executeList(int limit, boolean fresh, boolean dryRun) {
        long started = System.currentTimeMillis();
        try (AppBootstrap boot = AppBootstrap.create(config, operationsOverride)) {
            state.listTotal(boot.targets().runnable().size());
            TargetListTask.Summary summary = boot.targetListTask().run(
                    TargetListTask.Options.of(limit, dryRun, fresh),
                    target -> {
                        state.listStep();
                        state.currentTarget(target.label());
                        state.log("按顺序下载：" + target.label());
                    });
            String status = summary.failed() == 0 ? "SUCCESS"
                    : (summary.success() > 0 ? "PARTIAL" : "FAILED");
            state.addResult(status, summary.successFiles(), summary.failedFiles(), summary.skipped(),
                    summary.apiCalls(), System.currentTimeMillis() - started);
            state.log(summary.describe());
            if (!summary.failedTargets().isEmpty()) {
                state.log("失败目标：" + summary.failedTargets());
            }
        } catch (RuntimeException e) {
            log.warn("列表批量下载失败", e);
            state.log("[ERROR] 列表批量下载失败：" + e.getMessage());
            state.fail(e.getClass().getSimpleName() + "：" + e.getMessage());
        } finally {
            state.finish();
            state.log("任务结束");
        }
    }

    /** 校验 ID 列表（后台线程）。 */
    private void executeCheck(boolean onlyPending) {
        long started = System.currentTimeMillis();
        try (AppBootstrap boot = AppBootstrap.create(config, operationsOverride)) {
            TargetListService.CheckReport report = boot.targetListService().checkAll(onlyPending);
            for (Target t : boot.targets().list()) {
                state.log(String.format("  %-18s %-4s %s", t.screenName(),
                        t.status() == com.xmedia.downloader.model.TargetStatus.INVALID ? "无效" : "有效",
                        t.status() == com.xmedia.downloader.model.TargetStatus.INVALID ? t.lastError() : t.label()));
            }
            state.addResult(report.invalidCount() > 0 ? "PARTIAL" : "SUCCESS",
                    (int) report.validCount(), (int) report.invalidCount(), 0, 0,
                    System.currentTimeMillis() - started);
            state.log("校验完成：有效 " + report.validCount() + " / 无效 " + report.invalidCount());
            if (report.invalidCount() > 0) {
                state.log("提醒：有 " + report.invalidCount() + " 个无效 ID（" + report.invalidNames()
                        + "），建议点「删除全部无效」清理");
            }
        } catch (RuntimeException e) {
            log.warn("校验 ID 列表失败", e);
            state.log("[ERROR] 校验失败：" + e.getMessage());
            state.fail(e.getClass().getSimpleName() + "：" + e.getMessage());
        } finally {
            state.finish();
            state.log("任务结束");
        }
    }

    // ------------------------------------------------------------ 工具
    private List<Map<String, String>> queryRows(String sql) {
        try (AppBootstrap boot = AppBootstrap.create(config, operationsOverride)) {
            return boot.database().read(conn -> {
                List<Map<String, String>> out = new ArrayList<>();
                try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                    int cols = rs.getMetaData().getColumnCount();
                    while (rs.next()) {
                        Map<String, String> row = new LinkedHashMap<>();
                        for (int i = 1; i <= cols; i++) {
                            row.put(rs.getMetaData().getColumnLabel(i), rs.getString(i));
                        }
                        out.add(row);
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException("查询失败：" + e.getMessage(), e);
                }
                return out;
            });
        }
    }

    private static int intParam(HttpExchange exchange, String name, int def) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) {
            return def;
        }
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) {
                try {
                    return Integer.parseInt(kv[1]);
                } catch (NumberFormatException e) {
                    return def;
                }
            }
        }
        return def;
    }

    private static ObjectNode error(String message) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("error", message);
        return node;
    }

    private static void respondJson(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("content-type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void respondText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
    }
}
