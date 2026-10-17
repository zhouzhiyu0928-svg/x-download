package com.xmedia.downloader.console;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/** 操作台的共享运行状态（供 HTTP 接口读取，任务线程写入）。 */
public final class RunState {

    private static final int MAX_LOGS = 800;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final List<String> logs = Collections.synchronizedList(new LinkedList<>());

    private volatile boolean running;
    private volatile String task = "";
    private volatile List<String> targets = List.of();
    private volatile String currentTarget = "";
    private volatile int success;
    private volatile int failed;
    private volatile int skipped;
    private volatile int apiCalls;
    private volatile long startedAt;
    private volatile long finishedAt;
    private volatile String lastMessage = "";
    private volatile String lastError = "";
    private volatile Thread worker;
    /** 批量（按 ID 列表顺序）下载的进度：第几个 / 共几个 */
    private volatile int listDone;
    private volatile int listTotal;

    public void log(String line) {
        String stamped = TIME.format(Instant.now().atZone(ZoneId.systemDefault())) + "  " + line;
        synchronized (logs) {
            logs.add(stamped);
            while (logs.size() > MAX_LOGS) {
                logs.remove(0);
            }
        }
    }

    public List<String> logsSince(int fromIndex) {
        synchronized (logs) {
            if (fromIndex < 0 || fromIndex >= logs.size()) {
                return new ArrayList<>(logs);
            }
            return new ArrayList<>(logs.subList(fromIndex, logs.size()));
        }
    }

    public int logCount() {
        synchronized (logs) {
            return logs.size();
        }
    }

    public void begin(String task, List<String> targets, boolean dryRun) {
        this.task = task;
        this.targets = List.copyOf(targets);
        this.running = true;
        this.currentTarget = "";
        this.success = 0;
        this.failed = 0;
        this.skipped = 0;
        this.apiCalls = 0;
        this.lastError = "";
        this.listDone = 0;
        this.listTotal = 0;
        this.lastMessage = (dryRun ? "[dry-run] " : "") + "开始执行 " + task;
        this.startedAt = System.currentTimeMillis();
        this.finishedAt = 0L;
    }

    public void currentTarget(String target) {
        this.currentTarget = target;
    }

    public void listTotal(int total) {
        this.listTotal = total;
        this.listDone = 0;
    }

    /** 进入下一个列表条目（界面显示「3/12」）。 */
    public void listStep() {
        this.listDone++;
    }

    public void addResult(String status, int ok, int fail, int skip, int calls, long elapsed) {
        this.success += ok;
        this.failed += fail;
        this.skipped += skip;
        this.apiCalls += calls;
        this.lastMessage = String.format("%s：成功 %d / 失败 %d / 跳过 %d，API %d 次，耗时 %.1fs",
                status, ok, fail, skip, calls, elapsed / 1000.0);
    }

    public void fail(String message) {
        this.lastError = message;
        this.lastMessage = "执行失败：" + message;
    }

    public void finish() {
        this.running = false;
        this.finishedAt = System.currentTimeMillis();
    }

    public boolean running() {
        return running;
    }

    public void worker(Thread thread) {
        this.worker = thread;
    }

    public Thread worker() {
        return worker;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("running", running);
        map.put("task", task);
        map.put("targets", targets);
        map.put("currentTarget", currentTarget);
        map.put("success", success);
        map.put("failed", failed);
        map.put("skipped", skipped);
        map.put("apiCalls", apiCalls);
        map.put("startedAt", startedAt);
        map.put("finishedAt", finishedAt);
        map.put("elapsedMillis", running ? System.currentTimeMillis() - startedAt
                : (finishedAt > 0 ? finishedAt - startedAt : 0));
        map.put("lastMessage", lastMessage);
        map.put("lastError", lastError);
        map.put("listDone", listDone);
        map.put("listTotal", listTotal);
        map.put("logCount", logCount());
        return map;
    }
}
