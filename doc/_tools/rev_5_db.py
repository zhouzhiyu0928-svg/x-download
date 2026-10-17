# -*- coding: utf-8 -*-
"""《数据库结构说明》V1.0 → V1.1 整改。
锚点式就地修改，保留原排版；save() 在最后一行。
"""
import revlib as R

P = 'D:/x down/doc/Twitter_X_媒体下载器_Java版_数据库结构说明.docx'
doc = R.load(P)

# ---------- 0. 版本 ----------
R.edit(doc, '版本：V1.0', 'V1.0', 'V1.1')

# ---------- 1. §2 表关系概览 ----------
R.edit(doc, '• user_profile（用户主页信息）',
       '与 download_record 通过 user_screen_name 逻辑关联。',
       '与 download_record 通过 user_rest_id 逻辑关联。')
p = R.find_p(doc, '• schema_version（结构版本）— 用于数据库迁移管理。')
R.insert_after(p, [
    '• sync_cursor（增量水位线）— 按用户记录已同步到的最大推文 ID 与最近同步时间，支撑增量同步提前终止分页。',
    '• quota_ledger（调用配额账本）— 按（账号 × 自然日）累计 GraphQL 调用次数，支撑每日配额管理与跨进程续跑。'])
R.edit(doc, '关系类型：均为逻辑关联', 'download_record 与 task_run 为多对一（可选）。',
       'download_record 与 task_run 为多对一（可选）；download_record 与 sync_cursor 为多对一；quota_ledger 为独立账本表。')

# ---------- 2. §3.1 download_record ----------
t = R.tbl_after(doc, '3.1 download_record')
R.set_cell(t.rows[R.row_of(t, 'media_url')].cells[3], '媒体原始 URL（保留展示；不再作为唯一键）')
R.set_cell(t.rows[R.row_of(t, 'user_screen_name')].cells[3], '所属用户 screen_name（展示用，可变更，非稳定键）')
# H5/S6：新增稳定键与变体字段
i = R.row_of(t, 'user_screen_name')
R.add_row_after(t, i, ['user_rest_id', 'TEXT', '', '用户 rest_id（稳定键，关联 user_profile.rest_id）'])
R.add_row_after(t, i + 1, ['media_key', 'TEXT', 'NOT NULL', '媒体稳定标识（X media_key；缺失时用 URL 摘要）'])
R.add_row_after(t, i + 2, ['variant_bitrate', 'INTEGER', '', '视频码率变体（可选）'])
# H1/S4：重试相关字段
j = R.row_of(t, 'error_message')
R.add_row_after(t, j, ['attempt_count', 'INTEGER', '', '已尝试次数（默认 0），用于重试上限控制'])
R.add_row_after(t, j + 1, ['last_attempt_at', 'TEXT', '', '最近一次尝试时间'])
# S3：状态枚举去掉 SKIPPED
R.set_cell(t.rows[R.row_of(t, 'status')].cells[3], 'SUCCESS / FAILED（不落 SKIPPED，跳过仅计数不落库）')

# 索引建议（H1/H5）
R.edit(doc, '• UNIQUE(tweet_id, media_url)', 'UNIQUE(tweet_id, media_url) — 防止同一媒体重复入库，支撑增量判断',
       'UNIQUE(tweet_id, media_key) — 防止同一媒体重复入库，支撑增量判断')
R.edit(doc, '• INDEX(user_screen_name)', 'INDEX(user_screen_name)', 'INDEX(user_rest_id)、INDEX(user_screen_name)（展示查询用）')
p = R.find_p(doc, '• INDEX(task_type)')
R.insert_after(p, ['• 增量判定只认 status=\'SUCCESS\'；FAILED 记录以 UPSERT 覆盖重试，不参与「已下载」判断'])

# ---------- 3. §3.2 user_profile ----------
t = R.tbl_after(doc, '3.2 user_profile')
R.set_cell(t.rows[R.row_of(t, 'screen_name')].cells[2], 'NOT NULL')
R.set_cell(t.rows[R.row_of(t, 'screen_name')].cells[3], '用户 screen_name（可变更，仅展示用）')
R.set_cell(t.rows[R.row_of(t, 'rest_id')].cells[2], 'UNIQUE, NOT NULL')
R.set_cell(t.rows[R.row_of(t, 'rest_id')].cells[3], '用户 rest_id（稳定键，关联键）')
R.edit(doc, '索引：UNIQUE(screen_name)', '索引：UNIQUE(screen_name)。与 download_record 通过 user_screen_name = screen_name 逻辑关联。',
       '索引：UNIQUE(rest_id)。与 download_record 通过 user_rest_id = rest_id 逻辑关联；用户改名时按 rest_id 更新本表 screen_name。')

# ---------- 4. §3.3 task_run ----------
t = R.tbl_after(doc, '3.3 task_run')
R.set_cell(t.rows[R.row_of(t, 'status')].cells[3],
           'RUNNING / SUCCESS / PARTIAL / FAILED / ABORTED（启动时遗留 RUNNING 自动收敛为 ABORTED）')
R.set_cell(t.rows[R.row_of(t, 'api_call_count')].cells[3], '本次运行的 API 调用次数（账号级日累计见 quota_ledger）')

# ---------- 5. 新增 §3.5 sync_cursor / §3.6 quota_ledger ----------
h2_tpl = R.find_p(doc, '3.4 schema_version（结构版本表）')
b_tpl = R.find_p(doc, '初始版本建议写入 version=1。后续加表或改字段时递增版本并执行迁移脚本。')
t_tpl = doc.tables[0]

R.add_subsection(
    doc, '4. 建表 SQL 参考', '3.5 sync_cursor（增量水位线表）',
    ['用途：按用户记录已同步到的最大推文 ID 与最近同步时间。增量同步时，若某一页推文全部早于水位线即可提前终止分页，'
     '避免每轮翻完整条时间线（直接节省调用配额）。索引：UNIQUE(user_rest_id)。'],
    [['字段名', '类型', '约束', '说明'],
     ['id', 'INTEGER', 'PK, AI', '自增主键'],
     ['user_rest_id', 'TEXT', 'UNIQUE, NOT NULL', '用户 rest_id（稳定键）'],
     ['screen_name', 'TEXT', '', '用户 screen_name（展示用）'],
     ['last_max_tweet_id', 'TEXT', '', '已同步到的最大推文 ID（水位线）'],
     ['last_synced_at', 'TEXT', '', '最近一次同步完成时间'],
     ['last_run_id', 'INTEGER', '', '关联 task_run.id（可选）'],
     ['updated_at', 'TEXT', 'NOT NULL', '最后更新时间']],
    h2_tpl, b_tpl, t_tpl)

R.add_subsection(
    doc, '4. 建表 SQL 参考', '3.6 quota_ledger（调用配额账本表）',
    ['用途：按（账号 × 自然日）累计 GraphQL 调用次数，支撑账号级每日配额管理与跨进程续跑；'
     '配额耗尽后不得再重试（退避无效），只能换号或次日续跑。索引：UNIQUE(account_name, day)。'],
    [['字段名', '类型', '约束', '说明'],
     ['id', 'INTEGER', 'PK, AI', '自增主键'],
     ['account_name', 'TEXT', 'NOT NULL', '账号名（Account.name）'],
     ['day', 'TEXT', 'NOT NULL', '自然日（yyyy-MM-dd，本地时区）'],
     ['call_count', 'INTEGER', 'NOT NULL', '当日已用调用次数'],
     ['limit_count', 'INTEGER', '', '当日配额上限（取自配置，便于诊断）'],
     ['updated_at', 'TEXT', 'NOT NULL', '最后更新时间']],
    h2_tpl, b_tpl, t_tpl)

# ---------- 6. §4 建表 SQL ----------
p = R.find_p(doc, '以下为 SQLite 建表语句参考（实现时可放在 Database 初始化逻辑中）：')
R.insert_after(p, [
    '连接参数（每个连接打开后执行）：PRAGMA journal_mode = WAL;  PRAGMA busy_timeout = 5000;  PRAGMA synchronous = NORMAL;  PRAGMA foreign_keys = ON;',
    '写库约定：并发下载线程不直接写库，结果经单写线程队列串行落库，避免 SQLITE_BUSY 被误判为下载失败。'])

R.insert_after(R.find_p(doc, '  media_url TEXT NOT NULL,'), ['  media_key TEXT NOT NULL,'])
R.insert_after(R.find_p(doc, '  media_type TEXT NOT NULL,'), ['  variant_bitrate INTEGER,'])
R.insert_after(R.find_p(doc, '  user_screen_name TEXT,'), ['  user_rest_id TEXT,'])
R.insert_after(R.find_p(doc, '  error_message TEXT,'), ['  attempt_count INTEGER DEFAULT 0,', '  last_attempt_at TEXT,'])
R.edit(doc, '  UNIQUE(tweet_id, media_url)', '  UNIQUE(tweet_id, media_url)', '  UNIQUE(tweet_id, media_key)')
R.insert_after(R.find_p(doc, 'CREATE INDEX IF NOT EXISTS idx_dr_task_type'),
               ['CREATE INDEX IF NOT EXISTS idx_dr_user_rest ON download_record(user_rest_id);'])
R.edit(doc, '  screen_name TEXT NOT NULL UNIQUE,', '  screen_name TEXT NOT NULL UNIQUE,', '  screen_name TEXT NOT NULL,')
R.edit(doc, '  rest_id TEXT, name TEXT, description TEXT,', '  rest_id TEXT,',
       '  rest_id TEXT NOT NULL UNIQUE,')
R.insert_before(R.find_p(doc, '-- schema_version'), [
    '-- sync_cursor',
    'CREATE TABLE IF NOT EXISTS sync_cursor (',
    '  id INTEGER PRIMARY KEY AUTOINCREMENT,',
    '  user_rest_id TEXT NOT NULL UNIQUE, screen_name TEXT,',
    '  last_max_tweet_id TEXT, last_synced_at TEXT, last_run_id INTEGER,',
    '  updated_at TEXT NOT NULL',
    ');',
    '-- quota_ledger',
    'CREATE TABLE IF NOT EXISTS quota_ledger (',
    '  id INTEGER PRIMARY KEY AUTOINCREMENT,',
    '  account_name TEXT NOT NULL, day TEXT NOT NULL,',
    '  call_count INTEGER NOT NULL DEFAULT 0, limit_count INTEGER,',
    '  updated_at TEXT NOT NULL,',
    '  UNIQUE(account_name, day)',
    ');'])

# ---------- 7. §5 ER 图（等宽调整） ----------
def fit(p, inner):
    total = len(p.text)
    R.set_text(p, '│     ' + inner.ljust(total - 7) + '│')

R.edit(doc, 'UK screen_name', 'UK screen_name     ', 'UK rest_id         ')
R.edit(doc, 'UK (tweet_id, media_url)', 'UK (tweet_id, media_url)', 'UK (tweet_id, media_key) ')
fit(R.find_p(doc, 'media_type, local_path, user_screen_name'), 'media_type, local_path, user_rest_id, media_key')
fit(R.find_p(doc, 'task_type, status, error_message, file_size'), 'task_type, status, error_message, attempt_count')
fit(R.find_p(doc, 'tweet_created_at, downloaded_at, task_run_id'), 'tweet_created_at, downloaded_at, task_run_id')
p = R.find_p(doc, '└─────────────────────┘')
R.insert_after(p, ['', '注：另有 sync_cursor（按用户水位线）与 quota_ledger（账号 × 日 调用配额账本）两张辅助表未画入上图，二者均为独立表、无强外键。'])

# ---------- 8. §6 典型查询 ----------
R.edit(doc, '• 增量判断：', 'SELECT 1 FROM download_record WHERE tweet_id=? AND media_url=? LIMIT 1',
       "SELECT 1 FROM download_record WHERE tweet_id=? AND media_key=? AND status='SUCCESS' LIMIT 1")
R.edit(doc, '• 某用户已下载列表：', 'WHERE user_screen_name=? AND status=\'SUCCESS\'',
       "WHERE user_rest_id=? AND status='SUCCESS'")
R.edit(doc, '• 清理某用户缓存：', 'DELETE FROM download_record WHERE user_screen_name=?',
       'DELETE FROM download_record WHERE user_rest_id=?（配合三档清理：仅记录 / 记录+文件 / 含空目录）')
p = R.find_p(doc, '• 任务历史：')
R.insert_after(p, [
    '• 失败重试（UPSERT 覆盖）：INSERT INTO download_record(tweet_id, media_key, media_type, status, attempt_count, created_at) '
    "VALUES(?,?,?, 'SUCCESS', 1, ?) ON CONFLICT(tweet_id, media_key) DO UPDATE SET "
    'status=excluded.status, local_path=excluded.local_path, attempt_count=download_record.attempt_count+1;',
    '• 读取水位线：SELECT last_max_tweet_id FROM sync_cursor WHERE user_rest_id=?',
    '• 今日配额已用：SELECT call_count, limit_count FROM quota_ledger WHERE account_name=? AND day=?'])

# ---------- 9. §7 补充说明 ----------
R.edit(doc, '• 第一阶段可仅实现 download_record', '第一阶段可仅实现 download_record + schema_version，user_profile 与 task_run 在第二/三阶段按需启用。',
       '第一阶段实现 download_record + schema_version + sync_cursor + quota_ledger'
       '（后两者是增量同步与每日配额的必要支撑），user_profile 与 task_run 在第二/三阶段按需启用。')
R.edit(doc, '• 外键可为逻辑关联', '需在连接时执行 PRAGMA foreign_keys = ON。',
       '需在连接时执行 PRAGMA foreign_keys = ON；并发场景另需 journal_mode = WAL 与 busy_timeout（见第 4 节），写入经单写队列串行化。')

# ---------- 10. 文末：效力顺序 + 修订记录 ----------
R.add_tail_section(
    doc, '— 文档结束 —', '8. 文档效力与修订记录',
    ['文档效力顺序：本套文档共五份（需求分析、详细设计、类图说明、时序图与关键流程图、数据库结构说明）。'
     '效力顺序为：需求分析 ＞ 详细设计 ＞ 类图说明 / 时序图与关键流程图 / 数据库结构说明'
     '（三者同级，冲突时以详细设计为准）。出现冲突时，以顺序在前的文档为准。',
     '修订记录：'],
    [['版本', '日期', '说明', '备注'],
     ['V1.1', '2026-10-12',
      '依据评审意见整改：H1 增量判定改认 status=\'SUCCESS\' 且 FAILED 允许 UPSERT 覆盖（§3.1、§6）；'
      'H3 增连接 PRAGMA 与单写队列约定（§4、§7）；H5/S6 增 user_rest_id、media_key、variant_bitrate，'
      'user_profile 改用 rest_id 为稳定键（§3.1、§3.2、§5）；S3 删除 SKIPPED 枚举（§3.1）；'
      'S4 增 attempt_count / last_attempt_at（§3.1）；S5 新增 sync_cursor 表（§3.5）；'
      'H2 新增 quota_ledger 表（§3.6）；S7 task_run 增 ABORTED 与启动收敛说明（§3.3）；'
      'S8 清理缓存三档（§6）；S14 增文档效力顺序与本表',
      '评审后首版']],
    h_tpl=R.find_p(doc, '7. 补充说明'), b_tpl=R.find_p(doc, '不在数据库中存储 Cookie、auth_token 等敏感信息。'),
    t_tpl=doc.tables[0])

R.save(doc, P)
print('OK 数据库结构说明 -> V1.1')
