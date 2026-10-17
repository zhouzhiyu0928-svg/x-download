package com.xmedia.downloader.storage;

import com.xmedia.downloader.model.DownloadRecord;
import com.xmedia.downloader.model.MediaItem;
import com.xmedia.downloader.model.MediaType;
import com.xmedia.downloader.model.RecordStatus;
import com.xmedia.downloader.model.TaskType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * download_record 存取。
 *
 * <p>两条整改核心（H1）：
 * <ul>
 *   <li>「已下载」判定**只看 status='SUCCESS'** —— FAILED 记录不参与跳过，否则失败媒体在增量模式下永不重试；</li>
 *   <li>写入用 UPSERT（{@code ON CONFLICT(tweet_id, media_key) DO UPDATE}）并累加 {@code attempt_count}，
 *       失败项可被成功结果覆盖。</li>
 * </ul>
 */
public final class DownloadRecordRepository {

    private static final String KEY_SEP = "\u0001";

    private final Database db;

    public DownloadRecordRepository(Database db) {
        this.db = db;
    }

    public static String key(String tweetId, String mediaKey) {
        return tweetId + KEY_SEP + mediaKey;
    }

    public static String key(MediaItem item) {
        return key(item.tweetId(), item.mediaKey());
    }

    /** 仅当存在 SUCCESS 记录时视为已下载（增量去重依据）。 */
    public boolean existsSuccess(String tweetId, String mediaKey) {
        return db.read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM download_record WHERE tweet_id=? AND media_key=? AND status='SUCCESS' LIMIT 1")) {
                Database.bind(ps, tweetId, mediaKey);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException e) {
                throw new IllegalStateException("查询下载记录失败：" + e.getMessage(), e);
            }
        });
    }

    /** 批量判定，返回其中「已成功下载」的键集合（tweetId\\u0001mediaKey）。 */
    public Set<String> downloadedKeys(Collection<MediaItem> items) {
        if (items == null || items.isEmpty()) {
            return Set.of();
        }
        return db.read(conn -> {
            Set<String> out = new LinkedHashSet<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM download_record WHERE tweet_id=? AND media_key=? AND status='SUCCESS' LIMIT 1")) {
                for (MediaItem item : items) {
                    Database.bind(ps, item.tweetId(), item.mediaKey());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            out.add(key(item));
                        }
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("批量查询下载记录失败：" + e.getMessage(), e);
            }
            return out;
        });
    }

    /** UPSERT：成功/失败都写，冲突时累加尝试次数（失败者下次仍会被重试）。 */
    public void upsert(DownloadRecord record) {
        db.writer().submit(() -> db.read(conn -> {
            String now = Instant.now().toString();
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO download_record(
                        tweet_id, media_key, media_type, media_url, local_path,
                        user_rest_id, user_screen_name, task_type, status, error_message,
                        file_size, variant_bitrate, tweet_created_at, downloaded_at,
                        attempt_count, last_attempt_at, created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(tweet_id, media_key) DO UPDATE SET
                        status=excluded.status,
                        local_path=excluded.local_path,
                        media_url=excluded.media_url,
                        file_size=excluded.file_size,
                        variant_bitrate=excluded.variant_bitrate,
                        error_message=excluded.error_message,
                        downloaded_at=excluded.downloaded_at,
                        attempt_count=download_record.attempt_count + 1,
                        last_attempt_at=excluded.last_attempt_at
                    """)) {
                Database.bind(ps,
                        record.tweetId(), record.mediaKey(),
                        record.mediaType() == null ? null : record.mediaType().label(),
                        record.mediaUrl(), record.localPath(),
                        record.userRestId(), record.userScreenName(),
                        record.taskType() == null ? null : record.taskType().name(),
                        record.status().name(), record.errorMessage(),
                        record.fileSize(), record.variantBitrate(),
                        record.tweetCreatedAt(), record.downloadedAt(),
                        Math.max(record.attemptCount(), 1), now, now);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("写入下载记录失败：" + e.getMessage(), e);
            }
            return null;
        }));
    }

    public List<DownloadRecord> listFailed() {
        return query("SELECT * FROM download_record WHERE status='FAILED' ORDER BY id", null);
    }

    public List<DownloadRecord> listByRestId(String userRestId) {
        return query("SELECT * FROM download_record WHERE user_rest_id=? ORDER BY id", userRestId);
    }

    public long count(RecordStatus status) {
        return db.read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM download_record WHERE status=?")) {
                Database.bind(ps, status.name());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("统计下载记录失败：" + e.getMessage(), e);
            }
        });
    }

    /** 删除某用户的记录，返回删除行数（配合 {@link CacheClearMode} 三档清理）。 */
    public int deleteByRestId(String userRestId) {
        AtomicInteger deleted = new AtomicInteger();
        db.writer().submit(() -> db.read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM download_record WHERE user_rest_id=?")) {
                Database.bind(ps, userRestId);
                deleted.set(ps.executeUpdate());
            } catch (SQLException e) {
                throw new IllegalStateException("删除下载记录失败：" + e.getMessage(), e);
            }
            return null;
        }));
        db.writer().flush();
        return deleted.get();
    }

    private List<DownloadRecord> query(String sql, String arg) {
        return db.read(conn -> {
            List<DownloadRecord> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                if (arg != null) {
                    Database.bind(ps, arg);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("查询下载记录失败：" + e.getMessage(), e);
            }
            return out;
        });
    }

    private static DownloadRecord map(ResultSet rs) throws SQLException {
        String type = rs.getString("media_type");
        String task = rs.getString("task_type");
        Integer bitrate = rs.getObject("variant_bitrate") == null ? null : rs.getInt("variant_bitrate");
        return new DownloadRecord(
                rs.getString("tweet_id"),
                rs.getString("media_key"),
                type == null ? null : MediaType.fromLabel(type),
                rs.getString("media_url"),
                rs.getString("user_rest_id"),
                rs.getString("user_screen_name"),
                task == null ? null : TaskType.valueOf(task),
                RecordStatus.valueOf(rs.getString("status")),
                rs.getString("local_path"),
                rs.getString("error_message"),
                rs.getLong("file_size"),
                bitrate,
                rs.getString("tweet_created_at"),
                rs.getString("downloaded_at"),
                rs.getInt("attempt_count"));
    }

    /** 供调试/测试：取一条记录。 */
    public Optional<DownloadRecord> find(String tweetId, String mediaKey) {
        List<DownloadRecord> rows = db.read(conn -> {
            List<DownloadRecord> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM download_record WHERE tweet_id=? AND media_key=?")) {
                Database.bind(ps, tweetId, mediaKey);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("查询下载记录失败：" + e.getMessage(), e);
            }
            return out;
        });
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
