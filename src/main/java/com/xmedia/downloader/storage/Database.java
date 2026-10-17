package com.xmedia.downloader.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQLite 连接、PRAGMA 与建表迁移（H3/H5/S3–S8）。
 *
 * <p>连接参数：{@code journal_mode=WAL}、{@code busy_timeout=5000}、{@code synchronous=NORMAL}、
 * {@code foreign_keys=ON}；写入统一走 {@link WriteQueue} 单写线程。
 * 启动时把遗留的 {@code task_run.status='RUNNING'} 收敛为 {@code 'ABORTED'}（S7）。
 */
public final class Database implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Database.class);
    public static final int SCHEMA_VERSION = 1;

    private final Connection conn;
    private final WriteQueue writeQueue = new WriteQueue();
    private final Object readLock = new Object();
    private final Path file;

    private Database(Connection conn, Path file) {
        this.conn = conn;
        this.file = file;
    }

    public static Database open(String dbPath) {
        Path file = Path.of(dbPath).toAbsolutePath();
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法创建数据库目录：" + file.getParent(), e);
        }
        try {
            Connection conn = DriverManager.getConnection("jdbc:sqlite:" + file);
            conn.setAutoCommit(true);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode = WAL");
                st.execute("PRAGMA busy_timeout = 5000");
                st.execute("PRAGMA synchronous = NORMAL");
                st.execute("PRAGMA foreign_keys = ON");
            }
            Database db = new Database(conn, file);
            db.migrate();
            return db;
        } catch (SQLException e) {
            throw new IllegalStateException("打开数据库失败：" + file + "（" + e.getMessage() + "）", e);
        }
    }

    public Connection connection() {
        return conn;
    }

    public WriteQueue writer() {
        return writeQueue;
    }

    public Path file() {
        return file;
    }

    /** 建表 + 索引 + schema_version 种子；幂等。 */
    public void migrate() {
        List<String> ddl = List.of(
                """
                CREATE TABLE IF NOT EXISTS download_record (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  tweet_id TEXT NOT NULL,
                  media_key TEXT NOT NULL,
                  media_type TEXT NOT NULL,
                  media_url TEXT,
                  local_path TEXT,
                  user_rest_id TEXT,
                  user_screen_name TEXT,
                  task_type TEXT,
                  status TEXT NOT NULL,
                  error_message TEXT,
                  file_size INTEGER,
                  variant_bitrate INTEGER,
                  tweet_created_at TEXT,
                  downloaded_at TEXT,
                  attempt_count INTEGER NOT NULL DEFAULT 0,
                  last_attempt_at TEXT,
                  task_run_id INTEGER,
                  created_at TEXT NOT NULL,
                  UNIQUE(tweet_id, media_key)
                )""",
                "CREATE INDEX IF NOT EXISTS idx_dr_user_rest ON download_record(user_rest_id)",
                "CREATE INDEX IF NOT EXISTS idx_dr_user_screen ON download_record(user_screen_name)",
                "CREATE INDEX IF NOT EXISTS idx_dr_status ON download_record(status)",
                "CREATE INDEX IF NOT EXISTS idx_dr_task_type ON download_record(task_type)",
                """
                CREATE TABLE IF NOT EXISTS user_profile (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  rest_id TEXT NOT NULL UNIQUE,
                  screen_name TEXT NOT NULL,
                  name TEXT,
                  description TEXT,
                  avatar_url TEXT,
                  banner_url TEXT,
                  avatar_local_path TEXT,
                  banner_local_path TEXT,
                  followers_count INTEGER,
                  updated_at TEXT NOT NULL
                )""",
                """
                CREATE TABLE IF NOT EXISTS task_run (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  task_type TEXT NOT NULL,
                  target TEXT,
                  status TEXT NOT NULL,
                  total_count INTEGER,
                  success_count INTEGER,
                  failed_count INTEGER,
                  skipped_count INTEGER,
                  api_call_count INTEGER,
                  started_at TEXT NOT NULL,
                  finished_at TEXT,
                  duration_ms INTEGER,
                  message TEXT
                )""",
                "CREATE INDEX IF NOT EXISTS idx_tr_task_type ON task_run(task_type)",
                "CREATE INDEX IF NOT EXISTS idx_tr_started_at ON task_run(started_at)",
                """
                CREATE TABLE IF NOT EXISTS sync_cursor (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  user_rest_id TEXT NOT NULL UNIQUE,
                  screen_name TEXT,
                  last_max_tweet_id TEXT,
                  last_synced_at TEXT,
                  last_run_id INTEGER,
                  updated_at TEXT NOT NULL
                )""",
                """
                CREATE TABLE IF NOT EXISTS quota_ledger (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  account_name TEXT NOT NULL,
                  day TEXT NOT NULL,
                  call_count INTEGER NOT NULL DEFAULT 0,
                  limit_count INTEGER,
                  updated_at TEXT NOT NULL,
                  UNIQUE(account_name, day)
                )""",
                """
                CREATE TABLE IF NOT EXISTS target (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  screen_name TEXT NOT NULL UNIQUE COLLATE NOCASE,
                  note TEXT NOT NULL DEFAULT '',
                  status TEXT NOT NULL DEFAULT 'UNKNOWN',
                  rest_id TEXT NOT NULL DEFAULT '',
                  display_name TEXT NOT NULL DEFAULT '',
                  media_count INTEGER NOT NULL DEFAULT 0,
                  tweet_count INTEGER NOT NULL DEFAULT 0,
                  last_error TEXT NOT NULL DEFAULT '',
                  last_checked_at TEXT,
                  enabled INTEGER NOT NULL DEFAULT 1,
                  sort_order INTEGER NOT NULL DEFAULT 0,
                  created_at TEXT NOT NULL
                )""",
                """
                CREATE INDEX IF NOT EXISTS idx_target_order ON target(sort_order, id)""",
                """
                CREATE TABLE IF NOT EXISTS schema_version (
                  version INTEGER PRIMARY KEY,
                  applied_at TEXT NOT NULL,
                  description TEXT
                )""",
                "INSERT OR IGNORE INTO schema_version(version, applied_at, description) VALUES ("
                        + SCHEMA_VERSION + ", '" + Instant.now() + "', 'R1 初始结构（含 media_key / 水位线 / 配额账本）')",
                // S7：启动时收敛遗留 RUNNING
                "UPDATE task_run SET status='ABORTED' WHERE status='RUNNING'");
        synchronized (readLock) {
            try (Statement st = conn.createStatement()) {
                for (String sql : ddl) {
                    st.execute(sql);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("数据库迁移失败：" + e.getMessage(), e);
            }
        }
    }

    /**
     * 对象普查：表名 → 行数，另返回索引与唯一约束（落包/迁移验证用，等价于 information_schema 检查）。
     */
    public Map<String, Object> census() {
        Map<String, Object> out = new LinkedHashMap<>();
        synchronized (readLock) {
            try (Statement st = conn.createStatement()) {
                List<String> tables = new ArrayList<>();
                try (ResultSet rs = st.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
                    while (rs.next()) {
                        tables.add(rs.getString(1));
                    }
                }
                Map<String, Integer> rows = new LinkedHashMap<>();
                for (String t : tables) {
                    try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + t)) {
                        rows.put(t, rs.next() ? rs.getInt(1) : -1);
                    }
                }
                List<String> indexes = new ArrayList<>();
                try (ResultSet rs = st.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
                    while (rs.next()) {
                        indexes.add(rs.getString(1));
                    }
                }
                out.put("tables", rows);
                out.put("indexes", indexes);
                out.put("journalMode", pragma("journal_mode"));
                out.put("schemaVersion", pragma("schema_version"));
            } catch (SQLException e) {
                throw new IllegalStateException("数据库普查失败：" + e.getMessage(), e);
            }
        }
        return out;
    }

    private String pragma(String name) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA " + name)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** schema_version 当前值（迁移断言用）。 */
    public int schemaVersion() {
        synchronized (readLock) {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT MAX(version) FROM schema_version")) {
                return rs.next() ? rs.getInt(1) : 0;
            } catch (SQLException e) {
                return 0;
            }
        }
    }

    public <T> T read(SqlFunction<T> fn) {
        synchronized (readLock) {
            return fn.apply(conn);
        }
    }

    /** 在读锁内执行的回调（保证 SQLite 单连接不被并发使用）。 */
    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection connection);
    }

    @Override
    public void close() {
        writeQueue.close();
        synchronized (readLock) {
            try {
                conn.close();
            } catch (SQLException e) {
                log.warn("关闭数据库连接失败：{}", e.getMessage());
            }
        }
    }

    /** 仅供测试：把 PreparedStatement 参数按顺序绑定。 */
    static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a == null) {
                ps.setObject(i + 1, null);
            } else if (a instanceof Integer n) {
                ps.setInt(i + 1, n);
            } else if (a instanceof Long n) {
                ps.setLong(i + 1, n);
            } else if (a instanceof Boolean b) {
                ps.setInt(i + 1, b ? 1 : 0);
            } else {
                ps.setString(i + 1, a.toString());
            }
        }
    }
}
