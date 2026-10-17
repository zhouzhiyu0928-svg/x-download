# -*- coding: utf-8 -*-
"""《类图说明》V1.0 → V1.1 整改（H5/H6/S6/S9/S12/S13/S14 + P1/P5/P6）。
锚点式就地修改，保留原排版；save() 在最后一行。
"""
import revlib as R

P = 'D:/x down/doc/Twitter_X_媒体下载器_Java版_类图说明.docx'
doc = R.load(P)

R.edit(doc, '版本：V1.0', 'V1.0', 'V1.1')

# ---------- §3.1 交互层（P6） ----------
R.set_text(R.find_p(doc, '职责：解析命令行参数，加载配置，组装依赖，创建对应 Task 并提交 TaskScheduler。'),
           '职责：解析命令行参数，调用 AppBootstrap / ComponentFactory 装配依赖（装配细节见时序图 §3），'
           '创建对应 Task 并提交 TaskScheduler。')
t = R.tbl_after(doc, '3.1 MainCommand / 各子命令')
c = t.rows[R.row_of(t, 'UserCommand')].cells[1]
R.set_cell(c, c.text + '、account-binding（可选，Likes / 亮点 任务必填）')

# ---------- §4.2 AppConfig（S2） ----------
R.edit(doc, '  List<AccountConfig> accounts', '  List<AccountConfig> accounts',
       '  List<AccountConfig> accounts   // name / note（本人账号标记）/ cookie')
R.edit(doc, '  DownloadConfig download', 'enableIncremental ...',
       'enableIncremental, videoVariant, resumeEnabled ...')
R.insert_after(R.find_p(doc, '  RateLimitConfig rateLimit'), [
    '  String dbPath                // SQLite 文件路径',
    '  LogConfig log                // level / file / maxHistory',
    '  QuotaConfig quota            // perAccountDaily / reserveForRetry / estimateTweetsPerCall',
    '  ProxyConfig proxy            // mode: single | pool；list'])

# ---------- §5.1 GraphQLClient（P1） ----------
R.edit(doc, '<T> T execute(String operation, String queryId', 'String operation,',
       'String operationName,')
R.edit(doc, 'JsonNode executeRaw(String operation, String queryId', 'String operation,',
       'String operationName,')

# ---------- §5.3 Account / §5.4 AccountPool（H2/H6） ----------
R.insert_after(R.find_p(doc, '  Instant lastUsedAt / cooldownUntil'),
               ['  long quotaUsedToday / quotaRemaining   // 当日已用调用数与剩余配额'])
R.insert_after(R.find_p(doc, '  Account acquire()'),
               ['  Account acquire(String preferredName)   // 指定账号（Likes / 亮点 等必须）'])
R.insert_after(R.find_p(doc, '  void markInvalid(Account account)'),
               ['  void markQuotaExhausted(Account account)'])

# ---------- §6.3 / §6.4 任务体系（H6） ----------
t = R.tbl_after(doc, '6.3 具体 Task 类')
c = t.rows[R.row_of(t, 'UserMediaTask')].cells[1]
R.set_cell(c, c.text + '；可声明 accountBinding（AUTO / 指定账号），Likes 与亮点必须绑定本人账号')
R.insert_after(R.find_p(doc, '关系：均实现/继承 Task；通过 TaskContext 访问 Client、Downloader、Repository。'),
               ['• accountBinding：由 Command 注入、Task 读取；AUTO 表示可轮换，指定账号表示必须使用该账号。'])
R.insert_after(R.find_p(doc, '  ProgressCallback progressCallback'),
               ['  QuotaLedger quotaLedger   // 每日调用配额账本'])

# ---------- §7.1 MediaDownloader（H3） ----------
R.insert_after(R.find_p(doc, '  ExecutorService executor'),
               ['  DownloadResultQueue resultQueue   // 下载结果投递队列，落库由单写线程串行执行'])

# ---------- §7.2 NamingTemplate（S9） ----------
R.set_text(R.find_p(doc, '支持变量示例：'),
           '支持变量示例：{user} {date} {tweetId} {mediaKey} {type} {index} {ext}；'
           '其中 {index} 仅表示本轮运行内序号（重跑会变化），持久稳定命名请使用 {tweetId} / {mediaKey}；'
           '渲染结果落盘前经 PathSanitizer 清洗。')

# ---------- §7.3 Repository（H1/H5/S4/S8） ----------
R.set_text(R.find_p(doc, '  boolean exists(String tweetId, String mediaUrl)'),
           "  boolean exists(String tweetId, String mediaKey)   // 仅 status='SUCCESS' 视为已下载")
R.edit(doc, '  void save(DownloadRecord record)', '  void save(DownloadRecord record)',
       '  void save(DownloadRecord record)   // UPSERT：ON CONFLICT(tweet_id, media_key) DO UPDATE')
R.set_text(R.find_p(doc, '  void clearByUser(String screenName)'),
           '  void clearByUser(String restId, ClearMode mode)   // mode：仅记录 / 记录+文件 / 含空目录')
R.insert_after(R.find_p(doc, '  void clearByUser(String restId, ClearMode mode)   // mode：仅记录 / 记录+文件 / 含空目录'),
               ['  String getCursor(String restId) / void saveCursor(String restId, String lastMaxTweetId)'])

# ---------- §7.4 Database（H3/S7） ----------
R.edit(doc, '职责：管理 SQLite 连接与建表，供 Repository 使用。', '供 Repository 使用。',
       '供 Repository 使用；连接参数 journal_mode = WAL、busy_timeout = 5000、synchronous = NORMAL；'
       '写入经单写线程队列串行化。')
R.edit(doc, '关系：被 DownloadRecordRepository 依赖。', '关系：被 DownloadRecordRepository 依赖。',
       '关系：被 DownloadRecordRepository 依赖；启动时把遗留 task_run.status = RUNNING 收敛为 ABORTED。')

# ---------- §8 领域模型（P5） ----------
R.insert_after(R.find_p(doc, '关系：Tweet 包含多个 MediaItem；User 与 Tweet 关联；DownloadRecord 由 MediaItem 下载结果生成。'), [
    '字段补充（P5）：',
    '• ProgressCallback：onProgress(long done, long total, String currentTarget)、onItem(MediaItem item, String status)',
    '• TaskResult：taskName、status（SUCCESS / PARTIAL / FAILED / ABORTED）、successCount、failedCount、skippedCount、'
    'elapsedMs、failedItems、resumeCursor',
    '• DownloadSummary：successCount、failedCount、skippedCount、downloadedBytes、failedItems',
    '• MediaItem 增 mediaKey（稳定标识）与 variantBitrate（视频码率变体）'])

# ---------- §9 关系汇总（H2） ----------
R.insert_after(R.find_p(doc, '• AccountPool  →  Account（1 对多）'),
               ['• QuotaLedger → Account（按账号 × 自然日累计调用次数）'])

# ---------- 文末：效力顺序 + 修订记录 ----------
R.add_tail_section(
    doc, '— 文档结束 —', '12. 文档效力与修订记录',
    ['文档效力顺序：本套文档共五份（需求分析、详细设计、类图说明、时序图与关键流程图、数据库结构说明）。'
     '效力顺序为：需求分析 ＞ 详细设计 ＞ 类图说明 / 时序图与关键流程图 / 数据库结构说明（三者同级，冲突时以详细设计为准）。'
     '出现冲突时，以顺序在前的文档为准。',
     '修订记录：'],
    [['版本', '日期', '说明', '备注'],
     ['V1.1', '2026-10-12',
      '依据评审意见整改：H1/H5 Repository.exists 改按 (tweetId, mediaKey) 且仅认 SUCCESS（§7.3）；'
      'H2 Account/AccountPool 增配额字段与 markQuotaExhausted，AppConfig 增 quota / dbPath / log（§4.2、§5.3、§5.4）；'
      'H3 MediaDownloader 增结果投递队列（§7.1）；H6 增 accountBinding（§3.1、§6.3、§6.4）；'
      'S2 配置项补全（§4.2）；S6 MediaItem 增 mediaKey / variantBitrate（§8）；S9 命名模板稳定键（§7.2）；'
      'S14 增文档效力顺序与本表；P1 统一 operationName（§5.1）；P5 补三个模型的字段（§8）；'
      'P6 装配下沉 AppBootstrap（§3.1）；S8 clearByUser 三档（§7.3）',
      '评审后首版']],
    R.find_p(doc, '11. 补充说明'), R.find_p(doc, '以上类名与方法为设计阶段约定'),
    doc.tables[0])

R.save(doc, P)
print('OK 类图说明 -> V1.1')
