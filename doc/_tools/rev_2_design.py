# -*- coding: utf-8 -*-
"""《详细设计》V1.0 → V1.1 整改（H1-H6 / S1-S14 / P1-P7）。
锚点式就地修改，保留原排版；save() 在最后一行。
"""
import revlib as R

P = 'D:/x down/doc/Twitter_X_媒体下载器_Java版_详细设计.docx'
doc = R.load(P)

R.edit(doc, '版本：V1.0', 'V1.0', 'V1.1')

# ---------- 1. §2.2 模块职责 ----------
t = R.tbl_after(doc, '2.2 模块职责一览')
R.set_cell(t.rows[R.row_of(t, 'ratelimit')].cells[1],
           '请求间隔、并发信号量、指数退避、Rate Limit 响应处理、账号每日调用配额账本')
R.set_cell(t.rows[R.row_of(t, 'storage')].cells[1],
           'SQLite 访问、下载记录 CRUD、增量判断与水线位查询、缓存清理、单写队列管理')

# ---------- 2. §4.1.1 配置结构（整块重写） ----------
R.replace_section(doc, '配置文件采用 YAML 格式，主要节点如下：', '4.1.2 关键职责', [
    'save-path: "./downloads"',
    'db-path: "./data/xmedia.db"            # SQLite 数据库文件路径',
    'log:',
    '  level: INFO                          # ERROR / WARN / INFO / DEBUG',
    '  file: "./logs/xmedia.log"',
    '  max-history: 7                       # 日志轮转保留天数',
    'accounts:',
    '  - name: account1',
    '    note: "本人账号（likes / 亮点 任务必须绑定）"',
    '    cookie: "auth_token=xxx; ct0=yyy;"',
    'proxy:',
    '  mode: single                         # single | pool（pool 为预留，第二阶段实现）',
    '  list:',
    '    - "http://127.0.0.1:7890"',
    'download:',
    '  max-concurrent: 8',
    '  has-video: true',
    '  has-retweet: false',
    '  time-range: "1990-01-01:2030-01-01"',
    '  naming-template: "{user}/{date}_{tweetId}_{mediaKey}.{ext}"',
    '  enable-incremental: true',
    '  video-variant: highest               # highest | all | lowest',
    '  resume-enabled: true                 # 断点续传：*.part 临时文件 + 校验后原子改名',
    'rate-limit:',
    '  request-interval-ms: 500',
    '  max-retries: 3',
    'quota:',
    '  per-account-daily: 1000              # 账号每日 GraphQL 调用上限（按实测校准）',
    '  reserve-for-retry: 50                # 为失败重试预留的调用数',
    '  estimate-tweets-per-call: 19         # 启动前估算：调用次数 ≈ 推文数 / 19',
    '配置优先级：命令行参数 ＞ 环境变量（如 XMEDIA_*）＞ 配置文件 ＞ 内置默认值。',
    '启动前校验：Cookie 必填且格式合法、路径可写、并发数与间隔为正整数；并按 quota 估算本次所需调用数'
    '（目标推文数 ÷ estimate-tweets-per-call），若超出可用账号的剩余日配额，则拒绝启动并提示所需账号数量。',
    '命名模板可用变量：{user} {date} {tweetId} {mediaKey} {type} {index} {ext}。其中 {index} 仅表示本轮运行内序号，'
    '重跑会变化；需要持久稳定的文件名请使用 {tweetId} 与 {mediaKey}。文件名落盘前统一经 PathSanitizer 清洗'
    '（非法字符替换、长度截断、Windows 保留名规避）。'])

# ---------- 3. §4.2.1 GraphQLClient：请求头表（S11） ----------
old = '职责：统一发起 GraphQL 请求，组装 Header（含 cookie、x-csrf-token、x-client-transaction-id 等），处理响应解析与基础错误。'
p = R.find_p(doc, old)
R.set_text(p, '职责：统一发起 GraphQL 请求，组装完整请求头、解析响应与基础错误。请求头不完整时，即使 Cookie 有效也会被拒，'
              '下表为必须齐备的最小集合：')
R.make_table(doc, p, [
    ['请求头', '取值 / 来源', '是否可配置'],
    ['cookie', '账号池中该账号的 auth_token + ct0', '否（来自账号配置）'],
    ['x-csrf-token', '与 ct0 同值', '否'],
    ['x-client-transaction-id', 'TransactionIdGenerator 生成，按 operation 需要', '否（算法可插拔）'],
    ['authorization', '平台公开的 Web Bearer Token', '是（随前端版本更新）'],
    ['x-twitter-auth-type', 'OAuth2Session', '否'],
    ['x-twitter-active-user', 'yes', '否'],
    ['x-twitter-client-language', '语言代码（默认 en）', '是'],
    ['User-Agent', '固定为近期主流浏览器 UA', '是（需与 Bearer 版本匹配）'],
    ['content-type / accept', 'application/json', '否']],
    (0.26, 0.50, 0.24), doc.tables[0])

# ---------- 4. §4.2.2 TransactionIdGenerator（S13） ----------
R.set_text(R.find_p(doc, '职责：生成 X-Client-Transaction-ID。实现可替换'),
           '职责：生成 X-Client-Transaction-ID。实现可替换，便于在算法变更时快速适配。适用范围：仅 Tag / 评论区等'
           '受平台校验的 operation 需要该头，用户媒体时间线与纯文本获取通常不受影响；社区参考实现'
           '（iSarabjitDhiman/XClientTransaction）存在概率性失效，需持续跟进维护。')
R.set_text(R.find_p(doc, '接口建议：String generate(String path, String method)；'),
           '接口建议：String generate(String path, String method)；失败处理：有限次重试（建议 2 次）内重新生成，'
           '仍失败则抛出明确异常并提示「Transaction-ID 算法可能已变更」。')

# ---------- 5. 新增 §4.2.4 操作定义失效识别（S10） ----------
h3_tpl = R.find_p(doc, '4.2.3 请求拦截与重试')
b_tpl = R.find_p(doc, '通过 OkHttp Interceptor 实现')
h = R.insert_before(R.find_p(doc, '4.3 账号池（account）'), ['4.2.4 操作定义失效识别与恢复'], template=h3_tpl)[0]
R.insert_after(h, [
    '• 错误白名单：将 GraphQL 业务错误中「操作不存在 / queryId 失效 / features 不匹配」类错误码与消息文本纳入白名单，命中即判定为操作定义失效。',
    '• 明确提示：判定失效时抛出可读异常，提示用户更新 graphql-operations.yaml，而非笼统报「获取数据失败」。',
    '• 配置自述：graphql-operations.yaml 顶部标注所用版本、抓取日期与来源说明，便于比对与回滚。'],
    template=b_tpl)

# ---------- 6. §4.3 账号池（H6 / H2） ----------
R.edit(doc, '• Account：封装 name、cookie、ct0、authToken', '状态（可用/限流中/失效）、最后使用时间等。',
       '状态（可用/限流中/失效）、最后使用时间、当日已用调用数与剩余配额（quotaUsedToday / quotaRemaining）等。')
R.edit(doc, '• AccountPool：维护账号列表', '提供 acquire() / release() / markRateLimited() / markInvalid() 等方法。',
       '提供 acquire() / acquire(String preferredName) / release() / markRateLimited() / markInvalid() / '
       'markQuotaExhausted() 等方法。')
R.edit(doc, '轮换策略：优先选择当前可用且最近未使用的账号', '全部限流时进入等待或抛出友好异常。',
       '全部限流时进入等待或抛出友好异常；若任务声明 accountBinding 指定账号（Likes / 仅亮点 等「本人账号」语义），'
       '必须使用该账号，不得轮换。')

# ---------- 7. §4.4 限流器：日配额模型（H2） ----------
p = R.find_p(doc, '• 与 AccountPool 协同：限流账号自动切换到其他可用账号。')
R.insert_after(p, [
    '• 日调用配额：按（账号 × 自然日）累计 GraphQL 调用次数，落库于 quota_ledger 表；区别于频率限流，'
    '配额耗尽后退避与降并发均无效，只能换号或次日续跑。',
    '• 配额耗尽处理：收到平台的每日配额耗尽响应时，标记账号（markQuotaExhausted）并抛 QuotaExhaustedException，'
    '禁止继续重试。',
    '• 启动前估算：按「目标推文数 ÷ estimate-tweets-per-call」估算所需调用数，超出可用账号剩余配额时拒绝启动并提示所需账号数量。'])

# ---------- 8. §4.5.2 具体任务（H6） ----------
t = R.tbl_after(doc, '4.5.2 具体任务')
c = t.rows[R.row_of(t, 'UserMediaTask')].cells[1]
R.set_cell(c, c.text + '；任务可声明 accountBinding（AUTO / 指定账号名），Likes 与仅亮点必须绑定本人账号')

# ---------- 9. §4.6 下载器（H3 / S4 / S9 / P2） ----------
R.edit(doc, '• MediaDownloader：接收 MediaItem 列表', '写入文件，并更新 Storage 状态。',
       '写入文件；下载结果不直接写库，而是投递到 Storage 的单写队列异步落库（避免 SQLite 并发写锁）。')
R.edit(doc, '• NamingTemplate：根据模板字符串与上下文变量', '（user、date、tweetId、type、index、ext 等）渲染最终文件名与相对路径。',
       '（user、date、tweetId、mediaKey、type、index、ext 等）渲染最终文件名与相对路径；默认模板使用 {tweetId}/{mediaKey} '
       '等稳定键，{index} 仅为本轮运行内序号。')
R.edit(doc, '• 下载失败时按配置重试', '最终仍失败则记录到失败列表并继续后续任务。',
       '下载支持 HTTP Range 续传与 *.part 临时文件（校验通过后原子改名）；仍失败则写入 FAILED 记录'
       '（下次运行允许重试覆盖，不视为「已下载」）并继续后续任务。')
p = R.find_p(doc, '• 支持进度回调（已完成数 / 总数），供 CLI 刷新进度条或日志。')
R.insert_after(p, [
    '• 文件名与目录在落盘前经 PathSanitizer 清洗：非法字符替换、长度截断、Windows 保留名（CON / PRN / AUX 等）规避。'])

# ---------- 10. §4.7.1 数据表设计 ----------
t = R.tbl_after(doc, '4.7.1 数据表设计')
R.set_cell(t.rows[R.row_of(t, 'media_url')].cells[2], '媒体原始 URL（展示用；唯一约束改用 tweet_id + media_key）')
R.set_cell(t.rows[R.row_of(t, 'status')].cells[2], 'SUCCESS / FAILED（跳过仅计数，不落库）')
R.edit(doc, '可选扩展表：user_profile（主页信息）、task_run（任务执行历史）等。', '可选扩展表：',
       '完整字段与索引以《数据库结构说明》为准（含 user_rest_id、media_key、variant_bitrate、attempt_count、'
       'sync_cursor、quota_ledger）。可选扩展表：')

# ---------- 11. §4.7.2 Repository ----------
R.edit(doc, '• exists(tweetId, mediaUrl)：判断是否已下载（增量依据）。', 'exists(tweetId, mediaUrl)',
       "exists(tweetId, mediaKey)：仅当存在 status='SUCCESS' 记录时视为已下载；FAILED 记录不参与跳过。")
R.edit(doc, '• save(record)：写入或更新记录。', 'save(record)：写入或更新记录。',
       'save(record)：UPSERT 写入（ON CONFLICT(tweet_id, media_key) DO UPDATE），允许失败项重试覆盖。')
R.edit(doc, '• listFailed() / clearCache(user) 等维护方法。', 'listFailed() / clearCache(user) 等维护方法。',
       'listFailed() / clearCache(user, mode)（mode：仅记录 / 记录+文件 / 含空目录）/ '
       'getCursor(user) / saveCursor(user, lastMaxTweetId) 等维护方法。')
R.edit(doc, '数据库文件默认放在用户目录或配置指定路径，程序启动时自动建表。', '程序启动时自动建表。',
       '程序启动时自动建表，并将遗留的 task_run.status = RUNNING 收敛为 ABORTED。'
       '连接参数：journal_mode = WAL、busy_timeout = 5000、synchronous = NORMAL；写入统一经单写线程队列串行化。')

# ---------- 12. §5.1 主流程 ----------
R.edit(doc, '   c. 对每条媒体查询 Storage，跳过已下载项（增量）。', '跳过已下载项（增量）。',
       '跳过已下载项（增量）：先按该用户的 sync_cursor 水位线提前终止分页，再对每条媒体按 '
       "(tweet_id, media_key) 查 status='SUCCESS' 记录跳过。")
R.edit(doc, '5. 异常路径：Rate Limit → 切换账号或退避后重试；Cookie 失效 → 标记账号并提示用户。',
       '异常路径：Rate Limit → 切换账号或退避后重试；Cookie 失效 → 标记账号并提示用户。',
       '异常路径：频率限流 → 切换账号或退避后重试；账号每日调用配额耗尽 → 优雅停机并写入续跑点（次日续跑）；'
       'Cookie 失效 → 标记账号并提示用户。')

# ---------- 13. §6 操作约定（S10 交叉引用） ----------
R.edit(doc, '实际 queryId 与 features 需与当前 X 前端保持同步', '并提供更新说明。',
       '并提供更新说明；失效识别与提示流程见 4.2.4。')

# ---------- 14. §7.1 异常分类（H2） ----------
p = R.find_p(doc, '• RateLimitException：触发限流，进入退避与账号切换流程。')
R.insert_after(p, ['• QuotaExhaustedException：账号每日调用配额耗尽，停止重试并记录续跑点（次日续跑）。'])

# ---------- 15. §8 部署与运行（P4 / P7） ----------
R.edit(doc, '• 使用 Maven/Gradle 打包为可执行 fat-jar（含依赖）。', '打包为可执行 fat-jar（含依赖）。',
       '打包为可执行 fat-jar（含依赖）：SQLite 驱动使用 sqlite-jdbc（无需系统安装 sqlite3），'
       'Windows / Linux / macOS 三平台各执行一次冒烟验证。')
p = R.find_p(doc, '• 配置文件、数据库文件、下载目录均可通过参数或配置指定，默认相对当前工作目录。')
R.insert_after(p, ['• 支持 --dry-run：仅加载并校验配置、估算所需调用数与可用配额，不发起真实请求。'])

# ---------- 16. §9 实现优先级（H4） ----------
R.set_text(R.find_p(doc, '第一阶段：Config + GraphQLClient（基础）'),
           '第一阶段：Config + GraphQLClient（基础，含完整请求头与 TxId 按需生成）+ AccountPool（多账号 Cookie 池）+ '
           'RateLimiter（含日配额账本）+ UserMediaTask + MediaDownloader（含断点续传）+ Storage（WAL + 单写队列 + 水位线）+ '
           '基础 CLI + --dry-run。')
R.set_text(R.find_p(doc, '第二阶段：多账号池、代理、TagSearchTask'),
           '第二阶段：代理（含代理池）、TagSearchTask、ReplyTask、TextTask、ProfileTask、进度与报告、accountBinding 完善。')

# ---------- 17. 文末：效力顺序 + 修订记录 ----------
R.add_tail_section(
    doc, '— 文档结束 —', '11. 文档效力与修订记录',
    ['文档效力顺序：本套文档共五份（需求分析、详细设计、类图说明、时序图与关键流程图、数据库结构说明）。'
     '效力顺序为：需求分析 ＞ 详细设计 ＞ 类图说明 / 时序图与关键流程图 / 数据库结构说明（三者同级，冲突时以详细设计为准）。'
     '出现冲突时，以顺序在前的文档为准。',
     '修订记录：'],
    [['版本', '日期', '说明', '备注'],
     ['V1.1', '2026-10-12',
      '依据评审意见整改：H2 引入日调用配额模型与 QuotaExhaustedException（§4.1.1、§4.4、§7.1）；'
      'H3 增单写队列与 SQLite PRAGMA 约定（§4.6、§4.7.2）；H4 第一阶段纳入多账号池（§9）；'
      'H5/S6 稳定键改 rest_id / media_key（§4.7.1、§4.7.2）；H6 引入 accountBinding（§4.3、§4.5.2）；'
      'S1 代理支持 single/pool（§4.1.1）；S2 配置全量字段（§4.1.1）；S3 状态去掉 SKIPPED（§4.7.1）；'
      'S4 断点续传定义（§4.6）；S5 水位线增量（§5.1）；S9 命名模板稳定键（§4.1.1、§4.6）；'
      'S10 新增 4.2.4 失效识别（§6）；S11 补请求头表（§4.2.1）；S12 配额与限流区分（§4.4）；'
      'S13 收敛 TxId 适用范围（§4.2.2）；S14 增效力顺序与本表；P1 统一 operationName；'
      'P2 PathSanitizer；P4 打包细节；P6 依赖装配下沉 AppBootstrap；P7 --dry-run 与单测约定',
      '评审后首版']],
    R.find_p(doc, '10. 文档说明'), R.find_p(doc, '本详细设计文档依据需求分析确定的功能范围编写'),
    doc.tables[0])

R.save(doc, P)
print('OK 详细设计 -> V1.1')
