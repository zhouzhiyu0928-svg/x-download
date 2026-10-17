package com.xmedia.downloader.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xmedia.downloader.model.DownloadRecord;
import com.xmedia.downloader.model.MediaItem;
import com.xmedia.downloader.model.MediaType;
import com.xmedia.downloader.model.RecordStatus;
import com.xmedia.downloader.model.TaskType;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 存储层：结构、WAL、UPSERT 语义（H1）、水位线（S5）、配额账本（H2）、RUNNING 收敛（S7）。 */
class StorageTest {

    @TempDir
    Path dir;

    private Database db;
    private DownloadRecordRepository records;
    private SyncCursorRepository cursors;
    private QuotaLedgerRepository quota;

    @BeforeEach
    void setUp() {
        db = Database.open(dir.resolve("xmedia.db").toString());
        records = new DownloadRecordRepository(db);
        cursors = new SyncCursorRepository(db);
        quota = new QuotaLedgerRepository(db);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private static DownloadRecord record(String tweetId, String mediaKey, RecordStatus status) {
        return new DownloadRecord(tweetId, mediaKey, MediaType.IMAGE, "https://pbs.twimg.com/media/" + mediaKey + ".jpg",
                "rest1", "user1", TaskType.USER_MEDIA, status,
                status == RecordStatus.SUCCESS ? "user1/x.jpg" : null,
                status == RecordStatus.SUCCESS ? null : "HTTP 503",
                status == RecordStatus.SUCCESS ? 1234L : 0L, null, "2026-10-11T00:00:00Z",
                status == RecordStatus.SUCCESS ? "2026-10-12T00:00:00Z" : null, 1);
    }

    private int scalar(String sql) {
        return db.read(conn -> {
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                return rs.next() ? rs.getInt(1) : -1;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    @DisplayName("结构普查：表齐全、WAL 生效、版本号=1")
    @SuppressWarnings("unchecked")
    void schemaCensus() {
        Map<String, Object> census = db.census();
        Map<String, Integer> tables = (Map<String, Integer>) census.get("tables");
        for (String expected : List.of("download_record", "user_profile", "task_run",
                "sync_cursor", "quota_ledger", "schema_version")) {
            assertTrue(tables.containsKey(expected), "缺表：" + expected + "，实际：" + tables.keySet());
        }
        assertEquals("wal", census.get("journalMode"));
        assertEquals(Database.SCHEMA_VERSION, db.schemaVersion());
        assertTrue(((List<String>) census.get("indexes")).contains("idx_dr_status"));
    }

    @Test
    @DisplayName("H1：FAILED 不参与「已下载」，成功结果 UPSERT 覆盖并累加尝试次数")
    void failedDoesNotBlockRetry() {
        records.upsert(record("t1", "m1", RecordStatus.FAILED));
        db.writer().flush();

        assertFalse(records.existsSuccess("t1", "m1"), "FAILED 记录不得被当成已下载");
        assertEquals(1, records.listFailed().size());

        records.upsert(record("t1", "m1", RecordStatus.SUCCESS));
        db.writer().flush();

        assertTrue(records.existsSuccess("t1", "m1"));
        DownloadRecord saved = records.find("t1", "m1").orElseThrow();
        assertEquals(RecordStatus.SUCCESS, saved.status());
        assertEquals(2, saved.attemptCount(), "冲突时应累加 attempt_count");
        assertEquals(1, scalar("SELECT COUNT(*) FROM download_record WHERE tweet_id='t1'"));
        assertTrue(records.listFailed().isEmpty(), "覆盖后不应再出现在失败列表");
    }

    @Test
    @DisplayName("批量判定只返回 SUCCESS 的键")
    void downloadedKeysOnlySuccess() {
        records.upsert(record("t1", "m1", RecordStatus.SUCCESS));
        records.upsert(record("t2", "m2", RecordStatus.FAILED));
        db.writer().flush();

        List<MediaItem> items = List.of(
                new MediaItem("t1", "m1", "u", MediaType.IMAGE, "https://x/1.jpg", "jpg", 1L, null),
                new MediaItem("t2", "m2", "u", MediaType.IMAGE, "https://x/2.jpg", "jpg", 1L, null),
                new MediaItem("t3", "m3", "u", MediaType.IMAGE, "https://x/3.jpg", "jpg", 1L, null));
        Set<String> keys = records.downloadedKeys(items);

        assertEquals(Set.of(DownloadRecordRepository.key("t1", "m1")), keys);
    }

    @Test
    @DisplayName("按用户清理：删除记录并返回行数")
    void deleteByRestId() {
        records.upsert(record("t1", "m1", RecordStatus.SUCCESS));
        records.upsert(record("t2", "m2", RecordStatus.FAILED));
        db.writer().flush();

        assertEquals(2, records.deleteByRestId("rest1"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM download_record"));
        assertEquals(0L, records.count(RecordStatus.SUCCESS));
    }

    @Test
    @DisplayName("S5：水位线 UPSERT 且空值不倒退")
    void cursorUpsert() {
        cursors.save(new SyncCursorRepository.Cursor("rest1", "user1", "100", "2026-10-11T00:00:00Z", null));
        db.writer().flush();
        assertEquals("100", cursors.lastMaxTweetId("rest1"));

        cursors.save(new SyncCursorRepository.Cursor("rest1", "user1_renamed", "250", "2026-10-12T00:00:00Z", 7L));
        db.writer().flush();
        assertEquals("250", cursors.lastMaxTweetId("rest1"));
        assertEquals("user1_renamed", cursors.find("rest1").orElseThrow().screenName());

        // 传 null 不得把已有水位线抹掉（否则下轮会从头翻页，白烧配额）
        cursors.save(new SyncCursorRepository.Cursor("rest1", null, null, "2026-10-13T00:00:00Z", null));
        db.writer().flush();
        assertEquals("250", cursors.lastMaxTweetId("rest1"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM sync_cursor"));
    }

    @Test
    @DisplayName("H2：配额账本按账号×日累加")
    void quotaLedgerAccumulates() {
        LocalDate day = LocalDate.of(2026, 10, 12);
        assertEquals(0, quota.usedToday("account1", day));

        quota.addCalls("account1", day, 5, 1000);
        quota.addCalls("account1", day, 7, 1000);
        db.writer().flush();

        assertEquals(12, quota.usedToday("account1", day));
        assertEquals(12, quota.find("account1", day).orElseThrow().callCount());
        assertEquals(1000, quota.find("account1", day).orElseThrow().limitCount());
        assertEquals(0, quota.usedToday("account2", day), "不同账号独立计数");

        quota.reset("account1", day);
        assertEquals(0, quota.usedToday("account1", day));
    }

    @Test
    @DisplayName("S7：迁移时把遗留 task_run.status=RUNNING 收敛为 ABORTED")
    void runningConvergedToAborted() {
        db.read(conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute("INSERT INTO task_run(task_type, status, started_at) VALUES ('USER_MEDIA','RUNNING','2026-10-12T00:00:00Z')");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return null;
        });

        db.migrate();   // 模拟下次启动

        assertEquals(1, scalar("SELECT COUNT(*) FROM task_run WHERE status='ABORTED'"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM task_run WHERE status='RUNNING'"));
    }

    @Test
    @DisplayName("H3：写入经单写队列，flush 后可读（并发生成不撞锁）")
    void writesThroughQueue() {
        for (int i = 0; i < 200; i++) {
            records.upsert(record("t" + i, "m" + i, RecordStatus.SUCCESS));
        }
        db.writer().flush();
        assertEquals(200, scalar("SELECT COUNT(*) FROM download_record"));
        assertEquals(0, db.writer().pending());
    }
}
