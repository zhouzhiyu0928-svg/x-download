package com.xmedia.downloader.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xmedia.downloader.storage.Database;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 统计报告（F11）：CSV 带 BOM 且列齐、Markdown 含总览/分作者/失败明细。 */
class ReportGeneratorTest {

    @TempDir
    Path dir;

    private Database seed() {
        Database db = Database.open(dir.resolve("r.db").toString());
        db.read(conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute("""
                        INSERT INTO download_record(tweet_id, media_key, media_type, media_url, local_path,
                          user_rest_id, user_screen_name, task_type, status, file_size, downloaded_at,
                          attempt_count, created_at)
                        VALUES ('111','3_a','image','https://x/a.jpg','downloads/u1/a.jpg','1','u1','USER_MEDIA',
                          'SUCCESS', 1024, '2026-10-16T01:00:00Z', 1, '2026-10-16T01:00:00Z'),
                        ('222','3_b','image','https://x/b.jpg','downloads/u2/b.jpg','2','u2','TAG',
                          'FAILED', 0, NULL, 1, '2026-10-16T01:00:00Z')
                        """);
                st.execute("""
                        INSERT INTO task_run(task_type, target, status, success_count, failed_count,
                          skipped_count, api_call_count, duration_ms, started_at, finished_at)
                        VALUES ('USER_MEDIA','@u1','SUCCESS',1,0,0,3,4200,'2026-10-16T01:00:00Z','2026-10-16T01:00:04Z')
                        """);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
        return db;
    }

    @Test
    @DisplayName("CSV：UTF-8 BOM + 中文表头 + 两行数据")
    void csvReport() throws Exception {
        try (Database db = seed()) {
            ReportGenerator.Report report = new ReportGenerator(db)
                    .generate(dir.resolve("out"), Set.of("csv"), 50);

            assertEquals(2, report.recordCount());
            String csv = Files.readString(report.csv(), StandardCharsets.UTF_8);
            assertTrue(csv.startsWith("\uFEFF"), "CSV 应带 BOM（Excel 中文不乱码）");
            assertTrue(csv.contains("状态,任务类型,作者"), csv.substring(0, 80));
            assertTrue(csv.contains("SUCCESS,USER_MEDIA,u1"), csv);
            assertTrue(csv.contains("FAILED,TAG,u2"), csv);
        }
    }

    @Test
    @DisplayName("Markdown：总览统计、分类型、分作者、最近任务、失败明细")
    void markdownReport() throws Exception {
        try (Database db = seed()) {
            ReportGenerator.Report report = new ReportGenerator(db)
                    .generate(dir.resolve("out"), Set.of("md"), 50);

            String md = Files.readString(report.markdown(), StandardCharsets.UTF_8);
            assertTrue(md.contains("# X 媒体下载器 · 统计报告"));
            assertTrue(md.contains("| 下载成功 | 1 |"), md);
            assertTrue(md.contains("| 下载失败 | 1 |"), md);
            assertTrue(md.contains("| 覆盖作者数 | 2 |"), md);
            assertTrue(md.contains("1.0 KB") || md.contains("1024"), md);
            assertTrue(md.contains("| USER_MEDIA | 1 | 0 |"), md);
            assertTrue(md.contains("@u1"), md);
            assertTrue(md.contains("## 失败明细"), md);
            assertTrue(md.contains("3_b"), md);
            assertTrue(md.contains("最近任务"), md);
            assertEquals(1, report.taskRunCount());
        }
    }

    @Test
    @DisplayName("无失败的库：Markdown 显示「无失败记录」")
    void noFailures() throws Exception {
        try (Database db = Database.open(dir.resolve("empty.db").toString())) {
            ReportGenerator.Report report = new ReportGenerator(db)
                    .generate(dir.resolve("out2"), Set.of("md"), 50);
            String md = Files.readString(report.markdown(), StandardCharsets.UTF_8);
            assertTrue(md.contains("无失败记录"), md);
            assertEquals(0, report.recordCount());
        }
    }
}
