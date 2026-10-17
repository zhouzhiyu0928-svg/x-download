# -*- coding: utf-8 -*-
"""《时序图与关键流程图》V1.0 → V1.1 整改（H2/H3/H6/S4/S5/S9/S10/S12/S13/S14 + Q1/Q6）。
锚点式就地修改，保留原排版；save() 在最后一行。
"""
import revlib as R

P = 'D:/x down/doc/Twitter_X_媒体下载器_Java版_时序图与关键流程图.docx'
doc = R.load(P)

R.edit(doc, '版本：V1.0', 'V1.0', 'V1.1')

# ---------- §3 启动与依赖组装（P6：依赖装配下沉 AppBootstrap） ----------
R.set_text(R.find_p(doc, '4. Command：根据 AppConfig 创建 AccountPool、RateLimiter、TxIdGen、GraphQLClient、'),
           '4. Command → AppBootstrap：bootstrap(AppConfig)（依赖装配集中在 AppBootstrap / ComponentFactory，'
           '避免各子命令重复）')
R.set_text(R.find_p(doc, '         MediaDownloader、Repository、Database（建表若需要）'),
           '         AppBootstrap 创建 AccountPool、RateLimiter、QuotaLedger、TxIdGen、GraphQLClient、'
           'MediaDownloader、Repository、Database（建表，并将遗留 RUNNING 收敛为 ABORTED）')
R.edit(doc, '5. Command：创建具体 Task（如 UserMediaTask）并注入参数', '并注入参数',
       '并注入参数；若任务声明 accountBinding，一并注入账号名')
R.edit(doc, '6. Command → TaskScheduler：run(task) 或 runAll(tasks)', 'runAll(tasks)',
       'runAll(tasks)（任务结束或中断时写入 sync_cursor 续跑点）')
R.set_text(R.find_p(doc, '       ─是→ 初始化 AccountPool / Client / Downloader / DB'),
           '       ─是→ AppBootstrap 装配 AccountPool / Client / Downloader / DB / 配额账本')
R.insert_after(R.find_p(doc, '       ─是→ AppBootstrap 装配'),
               ['       → 启动前按推文数估算所需调用数，配额是否足够？ ─否→ 打印所需账号数量并退出'])

# ---------- §4.1 增量判定（H1/S5） ----------
R.set_text(R.find_p(doc, '   d. 对每条媒体：Repository.exists(tweetId, mediaUrl)'),
           "   d. 对每条媒体：Repository.exists(tweetId, mediaKey)（仅 status='SUCCESS' 视为已下载）")
R.set_text(R.find_p(doc, '      - 已存在且开启增量 → 计入 skip，不下载'),
           "      - 已存在（SUCCESS）且开启增量 → 计入 skip，不下载；FAILED 记录不参与跳过，允许重试覆盖")
R.set_text(R.find_p(doc, '   e. 若有 cursor 且未达停止条件'),
           '   e. 若有 cursor 且未达停止条件（含「整页推文均早于该用户水位线」）→ 继续分页；否则跳出循环')

# ---------- §4.2 流程图（S5） ----------
R.set_text(R.find_p(doc, '       → 解析并过滤 → 增量去重 → 累积待下载列表'),
           '       → 解析并过滤 → 水位线 / 增量去重 → 累积待下载列表')

# ---------- §5 单次 GraphQL 请求（H6/S12） ----------
R.set_text(R.find_p(doc, '1. Task → GraphQLClient：execute(operation, queryId, variables, type)'),
           '1. Task → GraphQLClient：execute(operationName, queryId, variables, type)')
R.set_text(R.find_p(doc, '3. GraphQLClient → AccountPool：acquire() → 得到 Account（cookie/ct0）'),
           '3. GraphQLClient → AccountPool：acquire(task.accountBinding) → 得到 Account（cookie / ct0）')
R.insert_after(R.find_p(doc, '3. GraphQLClient → AccountPool：acquire(task.accountBinding)'),
               ['3.1 若任务绑定指定账号（Likes / 仅亮点 等「本人账号」语义），acquire 必须返回该账号，不得轮换'])
R.set_text(R.find_p(doc, '   · 200 且业务成功 → 解析 JSON → RateLimiter.release()'),
           '   · 200 且业务成功 → 解析 JSON → 配额账本记 +1（quota_ledger）→ RateLimiter.release() → '
           'AccountPool.release() → 返回结果')
R.edit(doc, '   · 429 / Rate Limit → 见第 7 节', '（标记限流、退避、换号重试）',
       '（标记限流、退避、换号重试）；若为每日配额耗尽 → 见 7.4')
R.insert_after(R.find_p(doc, '   · Transaction-ID 被拒 → 重新 generate 后重试（有限次）'),
               ['   · 归还约定：以上所有分支必须在 finally 中释放（RateLimiter 与 AccountPool 释放顺序对称）；'
                '建议以 Lease 对象统一归还，避免信号量泄漏'])
R.insert_after(R.find_p(doc, ' |<---结果------|'),
               ['（注：ASCII 图仅示意成功分支的 release；实现时须在所有分支的 finally 中归还。）'])

# ---------- §6 媒体下载与落盘（H3/S4/S9） ----------
R.set_text(R.find_p(doc, '   a. NamingTemplate.render(item) → localPath'),
           '   a. NamingTemplate.render(item) → localPath（默认使用 {tweetId}/{mediaKey} 等稳定键，落盘前经 PathSanitizer 清洗）')
R.set_text(R.find_p(doc, '   b. 创建目录（若不存在）'), '   b. 创建目录（若不存在）；下载写入 *.part 临时文件')
R.set_text(R.find_p(doc, '   c. HTTP GET 媒体 URL（可走代理'),
           '   c. HTTP GET 媒体 URL（可走代理、支持 Range 续传；下载本身不消耗 GraphQL 调用配额）')
R.set_text(R.find_p(doc, '   d. 成功：写文件 → Repository.save(SUCCESS) → callback 进度'),
           '   d. 成功：校验大小 → 原子改名 → 结果投递单写队列（异步落库 SUCCESS）→ callback 进度')
R.set_text(R.find_p(doc, '   e. 失败：按配置重试；仍失败 → Repository.save(FAILED, error) → callback'),
           '   e. 失败：按配置重试；仍失败 → 投递 FAILED 记录（不视为「已下载」，下次运行允许重试覆盖）→ callback')
R.set_text(R.find_p(doc, '说明：增量“跳过”发生在提交下载之前'),
           '说明：增量「跳过」发生在提交下载之前（Task 内 exists 判断，仅 SUCCESS 记录参与跳过）；'
           'Downloader 侧仅处理 SUCCESS / FAILED；数据库写入统一经单写队列串行化，避免 SQLITE_BUSY 被误判为下载失败。')

# ---------- §7 Rate Limit（H2） ----------
R.edit(doc, '1. X API → GraphQLClient：429 或业务限流响应', '429 或业务限流响应',
       '429 或业务限流响应（需区分「频率限流」与「每日调用配额耗尽」，后者见 7.4）')

h = R.insert_before(R.find_p(doc, '8. 其他任务类型与主流程的差异'),
                    ['7.4 每日调用配额耗尽（与频率限流区分）'], template=R.find_p(doc, '7.3 认证失败'))[0]
R.insert_after(h, [
    '1. X API → GraphQLClient：返回每日调用配额耗尽（Rate limit exceeded）→ AccountPool.markQuotaExhausted(account)，并写入 quota_ledger',
    '2. 不退避、不重试：退避与降并发对「每日调用总量」无效',
    '3. 若任务声明 accountBinding 指定账号 → 无可换号，直接抛 QuotaExhaustedException',
    '4. 否则尝试其他账号（当日剩余配额 > 0）；全部耗尽 → 优雅停机',
    '5. 停机动作：Task 记录失败并写入 sync_cursor 续跑点，task_run.status 置 PARTIAL，'
    'CLI 输出配额消耗与次日续跑提示'],
    template=R.find_p(doc, '1. 401/403 或明确 Cookie 无效 → markInvalid(account)'))

# ---------- §9 进度回调与任务结束（S4） ----------
R.edit(doc, '5. 用户中断（Ctrl+C / 操作台停止）', '已在途请求尽量收尾后退出', '已在途请求尽量收尾后退出；中断同样写入续跑点')

# ---------- §11 补充说明（H2 / Q1） ----------
R.edit(doc, '• 下载媒体文件的 HTTP GET 与 GraphQL 调用分离', '下载失败走 Downloader 自身重试。',
       '下载失败走 Downloader 自身重试；下载不计入每日调用配额，配额只由 GraphQL 调用消耗'
       '（估算：调用次数 ≈ 推文数 / 19）。')
R.set_text(R.find_p(doc, '• 操作台若接后端，可将 Command 替换为 API 层，其后时序与 CLI 相同。'),
           '• 操作台原型（console.html）为演示页面，不在本期交付范围；若后续接入，可将 Command 替换为 API 层，'
           '其后时序与 CLI 相同。')

# ---------- 文末：效力顺序 + 修订记录 ----------
R.add_tail_section(
    doc, '— 文档结束 —', '12. 文档效力与修订记录',
    ['文档效力顺序：本套文档共五份（需求分析、详细设计、类图说明、时序图与关键流程图、数据库结构说明）。'
     '效力顺序为：需求分析 ＞ 详细设计 ＞ 类图说明 / 时序图与关键流程图 / 数据库结构说明（三者同级，冲突时以详细设计为准）。'
     '出现冲突时，以顺序在前的文档为准。',
     '修订记录：'],
    [['版本', '日期', '说明', '备注'],
     ['V1.1', '2026-10-12',
      '依据评审意见整改：H1 增量判定按 SUCCESS + media_key（§4.1）；H2 新增 7.4 每日配额耗尽流程与启动估算（§3、§7.4）；'
      'H3 落库经单写队列（§6）；H6 acquire(accountBinding)（§5）；S4 断点续传与 Range（§6）；'
      'S5 水位线提前终止分页（§3、§4.1、§4.2）；S9 命名稳定键（§6）；S12 finally / Lease 归还约定（§5）；'
      'S13 TxId 有限重试（§5）；S14 增文档效力顺序与本表；P6 依赖装配下沉 AppBootstrap（§3）；'
      '操作台范围说明（§11）',
      '评审后首版']],
    R.find_p(doc, '11. 补充说明'), R.find_p(doc, '• 多用户任务建议串行用户、用户内并发下载'),
    doc.tables[0])

R.save(doc, P)
print('OK 时序图 -> V1.1')
