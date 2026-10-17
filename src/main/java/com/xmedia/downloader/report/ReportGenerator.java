package com.xmedia.downloader.report;

import com.xmedia.downloader.storage.Database;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 统计报告（F11）：从本地数据库导出 CSV 与 Markdown。
 *
 * <p>CSV 带 UTF-8 BOM，Excel 直接打开不乱码；Markdown 面向人工阅读（总览 + 分类型 + 分作者 + 最近任务 + 失败明细）。
 */
public final class ReportGenerator {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Database database;

    public ReportGenerator(Database database) {
        this.database = database;
    }

    /** @param formats 取值 csv / md；@param failureLimit Markdown 里最多列多少条失败明细 */
    public Report generate(Path outDir, Set<String> formats, int failureLimit) throws IOException {
        Files.createDirectories(outDir);
        String stamp = STAMP.format(Instant.now().atZone(ZoneId.systemDefault()));
        List<Map<String, String>> rows = queryRecords();
        List<Map<String, String>> runs = queryTaskRuns();

        Path csv = null;
        Path md = null;
        if (formats.contains("csv")) {
            csv = outDir.resolve("download-report_" + stamp + ".csv");
            writeCsv(csv, rows);
        }
        if (formats.contains("md")) {
            md = outDir.resolve("download-report_" + stamp + ".md");
            writeMarkdown(md, rows, runs, failureLimit);
        }
        return new Report(csv, md, rows.size(), runs.size());
    }

    public record Report(Path csv, Path markdown, int recordCount, int taskRunCount) {
    }

    // ------------------------------------------------------------------ CSV
    private static final List<String> CSV_HEADERS = List.of(
            "状态", "任务类型", "作者", "推文ID", "媒体键", "媒体类型", "文件大小(字节)",
            "本地路径", "下载时间", "错误信息");

    private void writeCsv(Path file, List<Map<String, String>> rows) throws IOException {
        StringBuilder sb = new StringBuilder("\uFEFF");   // BOM：Excel 友好
        sb.append(String.join(",", CSV_HEADERS)).append("\r\n");
        for (Map<String, String> row : rows) {
            List<String> cells = new ArrayList<>();
            for (String h : CSV_HEADERS) {
                cells.add(csv(cell(row, h)));
            }
            sb.append(String.join(",", cells)).append("\r\n");
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    private String cell(Map<String, String> row, String header) {
        return switch (header) {
            case "状态" -> row.get("status");
            case "任务类型" -> row.get("task_type");
            case "作者" -> row.get("user_screen_name");
            case "推文ID" -> row.get("tweet_id");
            case "媒体键" -> row.get("media_key");
            case "媒体类型" -> row.get("media_type");
            case "文件大小(字节)" -> row.get("file_size");
            case "本地路径" -> row.get("local_path");
            case "下载时间" -> row.get("downloaded_at");
            default -> row.get("error_message");
        };
    }

    // ------------------------------------------------------------------ Markdown
    private void writeMarkdown(Path file, List<Map<String, String>> rows, List<Map<String, String>> runs,
                               int failureLimit) throws IOException {
        long success = rows.stream().filter(r -> "SUCCESS".equals(r.get("status"))).count();
        long failed = rows.size() - success;
        long bytes = rows.stream().filter(r -> "SUCCESS".equals(r.get("status")))
                .mapToLong(r -> parseLong(r.get("file_size"))).sum();
        long tweets = rows.stream().map(r -> r.get("tweet_id")).distinct().count();
        long users = rows.stream().map(r -> r.get("user_screen_name")).distinct().count();

        Map<String, long[]> byTask = new LinkedHashMap<>();
        Map<String, long[]> byAuthor = new LinkedHashMap<>();
        for (Map<String, String> r : rows) {
            boolean ok = "SUCCESS".equals(r.get("status"));
            long[] t = byTask.computeIfAbsent(String.valueOf(r.get("task_type")), k -> new long[2]);
            t[ok ? 0 : 1]++;
            long[] a = byAuthor.computeIfAbsent(String.valueOf(r.get("user_screen_name")), k -> new long[3]);
            a[ok ? 0 : 1]++;
            a[2] += ok ? parseLong(r.get("file_size")) : 0;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# X 媒体下载器 · 统计报告\n\n");
        sb.append("生成时间：").append(HUMAN.format(Instant.now().atZone(ZoneId.systemDefault()))).append("\n\n");
        sb.append("> 本工具仅供编程学习与技术研究使用；下载内容著作权归原作者与平台所有。\n\n");
        sb.append("## 总览\n\n| 指标 | 数值 |\n|---|---|\n");
        sb.append("| 下载成功 | ").append(success).append(" |\n");
        sb.append("| 下载失败 | ").append(failed).append(" |\n");
        sb.append("| 覆盖推文数 | ").append(tweets).append(" |\n");
        sb.append("| 覆盖作者数 | ").append(users).append(" |\n");
        sb.append("| 累计字节 | ").append(bytes).append("（").append(String.format("%.1f", bytes / 1024.0 / 1024.0))
                .append(" MB） |\n\n");

        sb.append("## 按任务类型\n\n| 任务类型 | 成功 | 失败 |\n|---|---|---|\n");
        byTask.forEach((k, v) -> sb.append("| ").append(k).append(" | ").append(v[0]).append(" | ").append(v[1])
                .append(" |\n"));
        sb.append('\n');

        sb.append("## 按作者（成功数 Top 20）\n\n| 作者 | 成功 | 失败 | 字节 |\n|---|---|---|---|\n");
        byAuthor.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(20)
                .forEach(e -> sb.append("| @").append(e.getKey()).append(" | ").append(e.getValue()[0])
                        .append(" | ").append(e.getValue()[1]).append(" | ").append(e.getValue()[2]).append(" |\n"));
        sb.append('\n');

        sb.append("## 最近任务\n\n| 时间 | 任务 | 目标 | 状态 | 成功/失败/跳过 | API 调用 | 耗时 |\n")
                .append("|---|---|---|---|---|---|---|\n");
        runs.stream().limit(10).forEach(r -> sb.append("| ").append(r.get("started_at")).append(" | ")
                .append(r.get("task_type")).append(" | ").append(r.get("target")).append(" | ")
                .append(r.get("status")).append(" | ").append(r.get("success_count")).append("/")
                .append(r.get("failed_count")).append("/").append(r.get("skipped_count")).append(" | ")
                .append(r.get("api_call_count")).append(" | ").append(r.get("duration_ms")).append("ms |\n"));
        sb.append('\n');

        List<Map<String, String>> failures = rows.stream()
                .filter(r -> !"SUCCESS".equals(r.get("status"))).limit(failureLimit).toList();
        sb.append("## 失败明细（最多 ").append(failureLimit).append(" 条，共 ").append(failed).append(" 条）\n\n");
        if (failures.isEmpty()) {
            sb.append("无失败记录。\n");
        } else {
            sb.append("| 推文ID | 媒体键 | 本地路径 | 错误 |\n|---|---|---|---|\n");
            for (Map<String, String> r : failures) {
                sb.append("| ").append(r.get("tweet_id")).append(" | ").append(r.get("media_key")).append(" | ")
                        .append(r.get("local_path")).append(" | ")
                        .append(String.valueOf(r.get("error_message")).replace("|", "\\|")).append(" |\n");
            }
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ 查询
    private List<Map<String, String>> queryRecords() {
        return query("SELECT status, task_type, user_screen_name, tweet_id, media_key, media_type, file_size,"
                + " local_path, downloaded_at, error_message FROM download_record ORDER BY id");
    }

    private List<Map<String, String>> queryTaskRuns() {
        return query("SELECT task_type, target, status, success_count, failed_count, skipped_count,"
                + " api_call_count, duration_ms, started_at FROM task_run ORDER BY id DESC");
    }

    private List<Map<String, String>> query(String sql) {
        return database.read(conn -> {
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
                throw new IllegalStateException("导出报告查询失败：" + e.getMessage(), e);
            }
            return out;
        });
    }

    private static long parseLong(String value) {
        try {
            return value == null ? 0L : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
