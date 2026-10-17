package com.xmedia.downloader.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/**
 * sync_cursor 存取（S5）：按用户维护增量水位线。
 *
 * <p>增量同步时，若某一页推文全部早于 {@code last_max_tweet_id}，即可提前终止分页 —— 直接节省调用配额。
 */
public final class SyncCursorRepository {

    private final Database db;

    public SyncCursorRepository(Database db) {
        this.db = db;
    }

    public record Cursor(String userRestId, String screenName, String lastMaxTweetId,
                         String lastSyncedAt, Long lastRunId) {
    }

    public Optional<Cursor> find(String userRestId) {
        return db.read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM sync_cursor WHERE user_rest_id=?")) {
                Database.bind(ps, userRestId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<Cursor>empty();
                    }
                    Object runId = rs.getObject("last_run_id");
                    return Optional.of(new Cursor(
                            rs.getString("user_rest_id"),
                            rs.getString("screen_name"),
                            rs.getString("last_max_tweet_id"),
                            rs.getString("last_synced_at"),
                            runId == null ? null : rs.getLong("last_run_id")));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取水位线失败：" + e.getMessage(), e);
            }
        });
    }

    public String lastMaxTweetId(String userRestId) {
        return find(userRestId).map(Cursor::lastMaxTweetId).orElse(null);
    }

    /** UPSERT 水位线；{@code lastMaxTweetId} 为 null 时保留原值（避免用空值倒退）。 */
    public void save(Cursor cursor) {
        db.writer().submit(() -> db.read(conn -> {
            String now = Instant.now().toString();
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO sync_cursor(user_rest_id, screen_name, last_max_tweet_id,
                                            last_synced_at, last_run_id, updated_at)
                    VALUES (?,?,?,?,?,?)
                    ON CONFLICT(user_rest_id) DO UPDATE SET
                        screen_name=COALESCE(excluded.screen_name, sync_cursor.screen_name),
                        last_max_tweet_id=COALESCE(excluded.last_max_tweet_id, sync_cursor.last_max_tweet_id),
                        last_synced_at=excluded.last_synced_at,
                        last_run_id=COALESCE(excluded.last_run_id, sync_cursor.last_run_id),
                        updated_at=excluded.updated_at
                    """)) {
                Database.bind(ps, cursor.userRestId(), cursor.screenName(), cursor.lastMaxTweetId(),
                        cursor.lastSyncedAt() == null ? now : cursor.lastSyncedAt(),
                        cursor.lastRunId(), now);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("写入水位线失败：" + e.getMessage(), e);
            }
            return null;
        }));
    }

    public int delete(String userRestId) {
        int[] deleted = new int[1];
        db.writer().submit(() -> db.read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sync_cursor WHERE user_rest_id=?")) {
                Database.bind(ps, userRestId);
                deleted[0] = ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("删除水位线失败：" + e.getMessage(), e);
            }
            return null;
        }));
        db.writer().flush();
        return deleted[0];
    }
}
