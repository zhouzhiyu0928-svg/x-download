package com.xmedia.downloader.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * quota_ledger 存取（H2）：按（账号 × 自然日）累计 GraphQL 调用次数。
 *
 * <p>跨进程可续：账本落库，重跑时接着算，避免「重启即忘记已用多少」导致超额。
 */
public final class QuotaLedgerRepository {

    private static final Logger log = LoggerFactory.getLogger(QuotaLedgerRepository.class);

    private final Database db;

    public QuotaLedgerRepository(Database db) {
        this.db = db;
    }

    public record LedgerRow(String accountName, String day, int callCount, Integer limitCount) {
    }

    public Optional<LedgerRow> find(String accountName, LocalDate day) {
        return db.read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM quota_ledger WHERE account_name=? AND day=?")) {
                Database.bind(ps, accountName, day.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<LedgerRow>empty();
                    }
                    Object limit = rs.getObject("limit_count");
                    return Optional.of(new LedgerRow(rs.getString("account_name"), rs.getString("day"),
                            rs.getInt("call_count"), limit == null ? null : rs.getInt("limit_count")));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取配额账本失败：" + e.getMessage(), e);
            }
        });
    }

    public int usedToday(String accountName, LocalDate day) {
        return find(accountName, day).map(LedgerRow::callCount).orElse(0);
    }

    /** 累加调用次数（原子 UPSERT），并可顺带刷新当日上限快照。 */
    public void addCalls(String accountName, LocalDate day, int calls, int limit) {
        if (calls <= 0) {
            return;
        }
        db.writer().submit(() -> db.read(conn -> {
            String now = Instant.now().toString();
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO quota_ledger(account_name, day, call_count, limit_count, updated_at)
                    VALUES (?,?,?,?,?)
                    ON CONFLICT(account_name, day) DO UPDATE SET
                        call_count = quota_ledger.call_count + excluded.call_count,
                        limit_count = excluded.limit_count,
                        updated_at = excluded.updated_at
                    """)) {
                Database.bind(ps, accountName, day.toString(), calls, limit, now);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("写入配额账本失败：{}", e.getMessage());
                throw new IllegalStateException("写入配额账本失败：" + e.getMessage(), e);
            }
            return null;
        }));
    }

    public void reset(String accountName, LocalDate day) {
        int[] deleted = new int[1];
        db.writer().submit(() -> db.read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM quota_ledger WHERE account_name=? AND day=?")) {
                Database.bind(ps, accountName, day.toString());
                deleted[0] = ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("重置配额账本失败：" + e.getMessage(), e);
            }
            return null;
        }));
        db.writer().flush();
    }
}
