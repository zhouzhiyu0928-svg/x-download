package com.xmedia.downloader.storage;

import com.xmedia.downloader.model.TaskResult;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/** task_run 记录：每次任务执行一行，便于事后追溯（S6/S7）。 */
public final class TaskRunRepository {

    private final Database db;

    public TaskRunRepository(Database db) {
        this.db = db;
    }

    /** 插入一条 RUNNING 记录并返回 id。 */
    public long start(String taskType, String target) {
        AtomicLong id = new AtomicLong();
        db.writer().submit(() -> db.read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO task_run(task_type, target, status, started_at)
                    VALUES (?,?,?,?)
                    """, PreparedStatement.RETURN_GENERATED_KEYS)) {
                Database.bind(ps, taskType, target, "RUNNING", Instant.now().toString());
                ps.executeUpdate();
                var keys = ps.getGeneratedKeys();
                if (keys.next()) {
                    id.set(keys.getLong(1));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("写入 task_run 失败：" + e.getMessage(), e);
            }
            return null;
        }));
        db.writer().flush();
        return id.get();
    }

    public void finish(long runId, TaskResult.Status status, int total, int success, int failed, int skipped,
                       int apiCalls, long durationMs, String message) {
        db.writer().submit(() -> db.read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE task_run SET status=?, total_count=?, success_count=?, failed_count=?, skipped_count=?,
                        api_call_count=?, finished_at=?, duration_ms=?, message=?
                    WHERE id=?
                    """)) {
                Database.bind(ps, status.name(), total, success, failed, skipped, apiCalls,
                        Instant.now().toString(), durationMs, message, runId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("更新 task_run 失败：" + e.getMessage(), e);
            }
            return null;
        }));
        db.writer().flush();
    }
}
