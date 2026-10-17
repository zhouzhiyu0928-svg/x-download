package com.xmedia.downloader.storage;

import com.xmedia.downloader.model.Target;
import com.xmedia.downloader.model.TargetStatus;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * target 表存取：ID 列表（可增加 / 可删除 / 可校验 / 按顺序下载）。
 *
 * <p>顺序由 {@code sort_order} 决定，批量下载按 (sort_order, id) 升序逐个执行；
 * 界面的「上移 / 下移」即与相邻条目交换 sort_order。
 */
public final class TargetRepository {

    /** X 的 screen_name：字母数字下划线，最长 15。 */
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9_]{1,15}$");
    private static final Pattern URL_FORM = Pattern.compile("^(?:https?://)?(?:www\\.)?(?:x|twitter)\\.com/([^/?#]+).*$",
            Pattern.CASE_INSENSITIVE);

    private final Database db;

    public TargetRepository(Database db) {
        this.db = db;
    }

    public record AddOutcome(Target target, boolean created) {
    }

    /**
     * 归一化用户输入：允许 {@code @name}、{@code x.com/name}、{@code https://twitter.com/name}、前后空白。
     *
     * @return 合法的 screen_name；不合法时返回 null（调用方负责提示）
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        var m = URL_FORM.matcher(s);
        if (m.matches()) {
            s = m.group(1);
        }
        s = s.replaceAll("^@+", "");
        // 去掉常见尾随噪声
        s = s.replaceAll("[\\s,，;；]+$", "");
        return VALID_NAME.matcher(s).matches() ? s : null;
    }

    public List<Target> list() {
        return query("SELECT * FROM target ORDER BY sort_order, id", null);
    }

    /** 参与批量下载的条目（启用且非无效），按列表顺序。 */
    public List<Target> runnable() {
        return query("SELECT * FROM target WHERE enabled=1 AND status<>'INVALID' ORDER BY sort_order, id", null);
    }

    public List<Target> invalid() {
        return query("SELECT * FROM target WHERE status='INVALID' ORDER BY sort_order, id", null);
    }

    public Target find(long id) {
        List<Target> rows = query("SELECT * FROM target WHERE id=?", ps -> ps.setLong(1, id));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long count() {
        return db.read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM target");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            } catch (SQLException e) {
                throw new IllegalStateException("统计 ID 列表失败：" + e.getMessage(), e);
            }
        });
    }

    public long invalidCount() {
        return db.read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM target WHERE status='INVALID'");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            } catch (SQLException e) {
                throw new IllegalStateException("统计无效 ID 失败：" + e.getMessage(), e);
            }
        });
    }

    /** 新增；已存在（忽略大小写）时不重复插入。 */
    public AddOutcome add(String rawName, String note) {
        String name = normalize(rawName);
        if (name == null) {
            throw new IllegalArgumentException("ID 格式非法（应为字母/数字/下划线，≤15 位）：" + rawName);
        }
        for (Target t : list()) {
            if (t.screenName().equalsIgnoreCase(name)) {
                // 已存在：若这次带了新备注，顺手更新（界面「加入列表」重复提交时不会白写）
                if (note != null && !note.isBlank() && !note.equals(t.note())) {
                    updateNote(t.id(), note);
                    return new AddOutcome(find(t.id()), false);
                }
                return new AddOutcome(t, false);
            }
        }
        int nextOrder = list().stream().mapToInt(Target::sortOrder).max().orElse(0) + 1;
        String now = Instant.now().toString();
        db.writer().submit(() -> db.read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO target(screen_name, note, status, rest_id, display_name, media_count,
                                       tweet_count, last_error, enabled, sort_order, created_at)
                    VALUES (?,?,'UNKNOWN','','',0,0,'',1,?,?)
                    """)) {
                Database.bind(ps, name, note == null ? "" : note, nextOrder, now);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("新增 ID 失败：" + e.getMessage(), e);
            }
            return null;
        }));
        db.writer().flush();
        return new AddOutcome(findByName(name), true);
    }

    public Target findByName(String screenName) {
        List<Target> rows = query("SELECT * FROM target WHERE screen_name=? COLLATE NOCASE", ps -> ps.setString(1, screenName));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 只改备注（不动顺序与状态）。 */
    public void updateNote(long id, String note) {
        db.writer().submit(() -> db.read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE target SET note=? WHERE id=?")) {
                Database.bind(ps, note == null ? "" : note, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("更新备注失败：" + e.getMessage(), e);
            }
            return null;
        }));
        db.writer().flush();
    }

    public int remove(long id) {
        return delete("DELETE FROM target WHERE id=?", ps -> ps.setLong(1, id));
    }

    /** 删除全部无效条目（界面「一键删除无效」）。 */
    public int removeInvalid() {
        return delete("DELETE FROM target WHERE status='INVALID'", null);
    }

    private int delete(String sql, Binder binder) {
        int[] deleted = new int[1];
        db.writer().submit(() -> db.read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                if (binder != null) {
                    binder.bind(ps);
                }
                deleted[0] = ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("删除 ID 失败：" + e.getMessage(), e);
            }
            return null;
        }));
        db.writer().flush();
        return deleted[0];
    }

    /** 写回一次校验结果。 */
    public void updateCheck(TargetStatus status, String restId, String displayName,
                            int mediaCount, int tweetCount, String error, long id) {
        db.writer().submit(() -> db.read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE target SET status=?, rest_id=?, display_name=?, media_count=?, tweet_count=?,
                                      last_error=?, last_checked_at=?
                    WHERE id=?
                    """)) {
                Database.bind(ps, status.name(), restId == null ? "" : restId,
                        displayName == null ? "" : displayName, mediaCount, tweetCount,
                        error == null ? "" : error, Instant.now().toString(), id);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("更新 ID 校验结果失败：" + e.getMessage(), e);
            }
            return null;
        }));
        db.writer().flush();   // 写入是异步的：不回刷的话紧接着的读取会拿到旧值
    }

    public void setEnabled(long id, boolean enabled) {
        db.writer().submit(() -> db.read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE target SET enabled=? WHERE id=?")) {
                Database.bind(ps, enabled ? 1 : 0, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("更新 ID 启用状态失败：" + e.getMessage(), e);
            }
            return null;
        }));
        db.writer().flush();
    }

    /** 上移/下移：与相邻条目交换顺序；已在端点则不动。 */
    public boolean move(long id, int delta) {
        List<Target> ordered = list();
        int index = -1;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).id() == id) {
                index = i;
                break;
            }
        }
        int target = index + (delta < 0 ? -1 : 1);
        if (index < 0 || target < 0 || target >= ordered.size()) {
            return false;
        }
        Target a = ordered.get(index);
        Target b = ordered.get(target);
        db.writer().submit(() -> db.read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE target SET sort_order=? WHERE id=?")) {
                Database.bind(ps, b.sortOrder(), a.id());
                ps.executeUpdate();
                Database.bind(ps, a.sortOrder(), b.id());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("调整 ID 顺序失败：" + e.getMessage(), e);
            }
            return null;
        }));
        db.writer().flush();
        return true;
    }

    /** 按给定 id 顺序整体重排（拖拽/批量排序用；未列出的条目排在后面）。 */
    public void reorder(List<Long> orderedIds) {
        List<Target> all = list();
        List<Long> finalOrder = new ArrayList<>(orderedIds);
        all.stream().map(Target::id).filter(i -> !finalOrder.contains(i)).forEach(finalOrder::add);
        db.writer().submit(() -> db.read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE target SET sort_order=? WHERE id=?")) {
                for (int i = 0; i < finalOrder.size(); i++) {
                    Database.bind(ps, i + 1, finalOrder.get(i));
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                throw new IllegalStateException("重排 ID 失败：" + e.getMessage(), e);
            }
            return null;
        }));
        db.writer().flush();
    }

    // ------------------------------------------------------------ 内部
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private List<Target> query(String sql, Binder binder) {
        return db.read(conn -> {
            List<Target> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                if (binder != null) {
                    binder.bind(ps);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取 ID 列表失败：" + e.getMessage(), e);
            }
            return out;
        });
    }

    private static Target map(ResultSet rs) throws SQLException {
        String status = rs.getString("status");
        return new Target(
                rs.getLong("id"),
                rs.getString("screen_name"),
                rs.getString("note"),
                status == null ? TargetStatus.UNKNOWN : TargetStatus.valueOf(status),
                rs.getString("rest_id"),
                rs.getString("display_name"),
                rs.getInt("media_count"),
                rs.getInt("tweet_count"),
                rs.getString("last_error"),
                rs.getString("last_checked_at"),
                rs.getInt("enabled") != 0,
                rs.getInt("sort_order"),
                rs.getString("created_at"));
    }
}
