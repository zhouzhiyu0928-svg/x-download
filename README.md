# X Media Downloader（Twitter/X 媒体下载器 · Java 版）

Twitter/X media & text archiver — CLI + local web console. Java 17, single fat-jar, no external CDN.

本地单机的 Twitter(X) 内容归档工具：**按用户 / Tag·高级搜索 / 评论区 / 纯文本 / 主页** 五类任务抓取图片、视频与文本，
带 **ID 列表批量**、**增量去重**、**断点续传**、多账号轮换与限流、SQLite 状态、统计报告，以及一个
**零依赖的本地图形操作台**（浏览器里选任务、看进度与实时日志）。

> **免责声明**：本工具仅供**编程学习、技术研究与个人归档**使用。使用的是 X 非官方 GraphQL 接口，
> 请遵守平台条款与当地法律法规；请使用**专用小号** Cookie（Cookie 只保存在本地 `config.yaml`，不会上传任何服务器）。
> 下载内容的著作权归原作者与平台所有，**不得用于侵权传播或对外分发**。

---

## 简介

「X Media Downloader · Java 版」是一个本地单机的 Twitter/X 内容归档工具。支持按**用户、Tag/高级搜索、评论区、纯文本、主页**五类任务抓取图片、视频与文本；内置 **ID 列表**（可增删、一键校验、无效 ID 提醒并清理、按列表顺序批量下载）、**增量去重与断点续传**、多账号轮换与频率限流、SQLite 状态与统计报告，以及一个**零依赖的本地图形操作台**（浏览器里选任务、看进度与实时日志）。技术栈 Java 17 + Maven + OkHttp + SQLite + picocli，打包为单个可执行 jar；queryId 可一键从 X 客户端自动刷新，接口变更时无需改代码。仅供编程学习与技术研究、个人归档使用，请使用专用小号 Cookie；下载内容的版权归原作者与平台所有，不得用于侵权传播或对外分发。

## 特性

- **五类任务**：`user`（用户媒体）、`tag`（Tag / 高级搜索）、`reply`（评论区）、`text`（纯文本）、`profile`（头像 / banner / 简介）
- **ID 列表**：多行粘贴批量增加（可在 ID 后空格写备注）、单条 / 批量删除、**一键校验并提醒删除无效 ID**、上移下移调整顺序、停用 / 启用、**按列表顺序逐个批量下载**（跳过无效与停用项，单个失败不影响后续）
- **增量与去重**：以「推文ID + media_key」为稳定键，重复运行不重复下载、也不覆盖同名不同内容的文件；水位线让二轮几乎零请求（增量可关 / 可用 `--fresh` 重扫）
- **断点续传**：未下完的文件保留 `.part`，下次运行自动续传；下载完成后校验再原子改名
- **多账号客户端**：多 Cookie 轮换，触发限流自动切换账号；**每日调用次数默认不限制**（`quota.per-account-daily: 0`，可设正数启用上限）
- **本地图形操作台**：单文件页面 + JDK 内置 HTTP Server，无任何外部前端依赖（离线可用）；只监听 `127.0.0.1`，接口不回显 Cookie
- **抗接口变更**：queryId / features 全部外置在 `operations.yaml`，一条 `refresh-operations` 从 X 客户端 bundle 现查并回写，**不用重新打包**
- **可观测与可验证**：结构化日志（按天轮转）、CSV/Markdown 统计报告、`--dump-pages` 导出原始响应、127 个自动化测试 + 可复跑的验证脚本

## 环境要求

- **JDK 17+**（开发与验证使用 Temurin / MS JDK 17）
- **Maven 3.9+**（或使用仓库内的包装脚本，见下）
- 可选：HTTP/SOCKS 代理（默认支持 `proxy.mode: single`）

```bash
# 标准构建（构建 + 全部测试 + 打包 fat-jar）
mvn clean package

# 若本机 mvn 不可用，可用仓库自带包装脚本（读取 MVN_HOME，仅当本机 127.0.0.1:10808 可连时注入代理）
bash tools/mvn.sh clean package
bash tools/mvn.sh test

# 产物：target/x-media-downloader.jar（自包含，含依赖，可直接 java -jar）
```

> ⚠️ `mvn test` **不会**更新 jar。改完代码必须重新 `package`，否则跑的还是旧包（本项目最容易踩的坑）。

## 快速开始

```bash
# 1) 准备配置
cp config.example.yaml config.yaml
#    编辑 config.yaml：accounts[].cookie 填 "auth_token=...; ct0=...;"

# 2) 先干跑，只看清单（不下载、不写状态）
java -jar target/x-media-downloader.jar --dry-run user -u Susu0526m

# 3) 正式下载某个用户的媒体
java -jar target/x-media-downloader.jar user -u Susu0526m

# 4) 打开图形操作台（浏览器界面）
java -jar target/x-media-downloader.jar console        # http://127.0.0.1:8848/
```

## 命令行参考

```bash
JAR="target/x-media-downloader.jar"

# 用户媒体
java -jar $JAR user -u 用户A -u 用户B --limit 50      # 多用户；每个用户最多 50 条推文（0=不限）
java -jar $JAR user -u 用户A --fresh                  # 忽略增量水位线重扫（已成功的不重复下载）
java -jar $JAR --dry-run user -u 用户A                # 只列清单：不下载文件、不写状态与水位线

# Tag / 高级搜索（支持 #标签 与 from:/filter: 等查询语句）
java -jar $JAR tag -t "猫猫" -t "from:某人 filter:images" --limit 20

# 评论区（该推文 + 评论，目标给链接或纯 ID）
java -jar $JAR reply -r https://x.com/user/status/2110741460089983155
java -jar $JAR reply -r 2110741460089983155 --limit 5

# 纯文本（只存文本，不下载媒体）
java -jar $JAR text -u 用户A

# 主页信息（头像 / banner / 简介）
java -jar $JAR profile -u 用户A

# ID 列表：增加 / 查看 / 校验 / 删除 / 排序 / 批量下载
java -jar $JAR targets add 用户A -n 重点              # 支持 @名字、x.com/名字、多行粘贴（页面）
java -jar $JAR targets add 用户B "#临时观察"          # 末位参数以 # 开头视为备注
java -jar $JAR targets list                          # 序号 / 状态 / 推文数 / 媒体数 / 备注
java -jar $JAR targets check                         # 校验未校验与已判无效的
java -jar $JAR targets check --all                   # 全部重新校验
java -jar $JAR targets remove-invalid                # 一键删除全部无效 ID
java -jar $JAR targets remove 2 5                    # 按序号删除（序号见 list）
java -jar $JAR targets move 3 up                     # 调整顺序：move <序号> up|down
java -jar $JAR targets toggle 4                      # 停用 / 启用（停用=保留但不参与批量下载）
java -jar $JAR targets run --limit 50                # 按列表顺序逐个下载（跳过无效/停用）

# 统计报告（CSV + Markdown）
java -jar $JAR report --out ./reports --format csv,md

# 本地图形操作台
java -jar $JAR console                      # 默认 8848，自动开浏览器
java -jar $JAR console -p 9000 --no-browser # 换端口 / 不开浏览器；Ctrl+C 退出

# queryId 过期时自更新（症状：HTTP 404 且响应体为空）
java -jar $JAR --dry-run refresh-operations  # 先预览会改哪些
java -jar $JAR refresh-operations            # 回写 operations.yaml（自动备份 .bak-<时间>）
```

**全局选项**（写在子命令之前）：

| 选项 | 说明 |
|---|---|
| `--dry-run` | 只加载配置、装配数据库、**列清单**；不下载文件、不写状态与水位线（枚举清单仍需发查询请求） |
| `--limit N` | 子命令级：最多处理 N 条推文（`0`=不限） |
| `--fresh` | 子命令级：忽略增量水位线，从头扫描 |
| `--dump-pages DIR` | 子命令级：把每页原始 JSON 导出到 DIR（排障用） |
| `-c, --config FILE` | 配置文件路径（默认 `config.yaml`） |
| `--save-path` / `--db-path` / `--max-concurrent` / `--naming-template` | 覆盖配置（CLI 优先级最高） |
| `--operations FILE` | 覆盖内置 `operations.yaml`（queryId 热更新，免重新打包） |
| `--tweets N` | 给出时做「启动前调用量估算」 |

## 配置说明

优先级：**CLI 参数 ＞ 环境变量（`XMEDIA_*`）＞ `config.yaml` ＞ 内置默认值**。

| 配置项 | 说明 |
|---|---|
| `save-path` / `db-path` | 下载根目录（默认 `./downloads`）/ SQLite 文件（默认 `./data/xmedia.db`） |
| `accounts[].cookie` | `auth_token=...; ct0=...;`（≥2 个账号时自动轮换，触发限流会切换） |
| `log.level` / `log.file` / `log.max-history` | 日志级别、文件、保留天数（按天轮转） |
| `proxy.mode` / `proxy.list` | `single`（当前支持）/ `pool`（预留） |
| `download.max-concurrent` | 并发下载数（1..64） |
| `download.has-video` / `has-retweet` | 是否下载视频 / 是否含转推 |
| `download.image-format` | `orig`（原图，默认）/ `4096x4096` / `jpg` / `png` |
| `download.video-variant` | `highest` / `all` / `lowest` |
| `download.time-range` | `YYYY-MM-DD:YYYY-MM-DD`（含结束当天） |
| `download.naming-template` | 默认 `{user}/{date}_{tweetId}_{mediaKey}.{ext}`（`{index}` 仅本轮有效，勿用于持久命名） |
| `download.enable-incremental` / `resume-enabled` | 增量跳过 / `.part` 断点续传 |
| `rate-limit.request-interval-ms` / `max-retries` | 请求最小间隔 / 单次请求最大重试 |
| `quota.per-account-daily` | 每账号每日 GraphQL 调用上限；**`0` = 不限制（默认）** |
| `tasks.tag.filter` / `product` / `save-text` | Tag 任务：`filter:images` 等过滤词、`Latest`/`Top`、是否同时存文本 |
| `tasks.reply.include-video` | 评论区是否下载视频 |
| `tasks.profile.*` | 是否下载头像 / banner / 保存简介 |

## 落盘结构

```
downloads/
├── <用户名>/                                  # user 任务
│   ├── 20261015_2235_2110741460089983155_3_xxx.jpg
│   ├── text/20261015_2235_2110741460089983155.txt      # text 任务
│   └── profile/{avatar.jpg,banner.jpg,description.txt} # profile 任务
├── tag/<查询词>/<作者>/                        # tag 任务
└── reply/<推文ID>/<作者>/                      # reply 任务
```

命名的稳定键是「**推文ID + media_key**」：重复运行不会重复下载，也不会互相覆盖；未下完的保留 `.part`，下次自动续传。

## 本地图形操作台

`java -jar $JAR console` → `http://127.0.0.1:8848/`（默认自动开浏览器，`-p` 换端口，`--no-browser` 抑制）

| 页签 | 内容 |
|---|---|
| **下载** | 选任务类型（user/tag/reply/text/profile）、填目标、限条数、`忽略水位线` / `只列清单` / `按 ID 列表顺序下载`；开始 / 停止；进度（含列表进度 `n/N`）与实时日志 |
| **ID 列表** | 增加（多行粘贴，ID 后可空格写备注）、上移 / 下移、停用 / 启用、删除；`校验` 标出无效 ID 并在顶部横幅提示 **`删除全部无效 ID`**；`按顺序全部下载` 从上到下逐个执行 |
| **账号** | 账号名、备注、状态、今日用量 / 上限 / 剩余（**不回显 Cookie**） |
| **设置** | 当前生效配置（只读）+ 一键生成统计报告 |

- 只绑定 `127.0.0.1`；`/api/*` 不返回 Cookie；页面无外部 CDN（离线可用）。
- 同一时刻只跑**一个任务**（含校验），「停止」为**中断语义**：未完成文件留 `.part`，下次续传。
- 执行历史与下载记录**不在界面展示**，但仍落库：`download_record` 是增量去重的账本（去掉它二轮会重下），
  `task_run` 供统计报告使用；需要时在「设置」页导出报告。

## 工作原理（要点）

- **接口层**：`operations.yaml` 外置每个操作（`queryId` / `features` / 是否需 POST / base-url），
  `GraphQLClient` 统一处理鉴权头、GET/POST、401/404/429 判定与重试；`QueryIdRefresher` 从 X 客户端 bundle 现查最新 queryId。
- **任务层**：`AbstractTimelineTask` 抽出翻页 / 去重 / 限流 / 水位线循环，五类任务只实现差异点；
  `TargetListTask` 在列表顺序上逐个调用，单个目标失败不影响后续。
- **存储层**：SQLite（WAL）记录下载记录、水位线、配额账本、ID 列表；写入走单写队列。
- **网络加固**：代理注入、UA/Bearer/Cookie/`x-csrf-token` 头、请求间隔与并发上限、失败重试与账号切换。

## 测试与验证

```bash
mvn clean package          # 或 bash tools/mvn.sh clean package —— 构建 + 127 个测试
bash tools/verify-r4.sh    # 任务/报告/操作台冒烟断言
bash tools/verify-r5.sh    # ID 列表 + 无次数上限断言（用独立临时库，不动你的数据）
```

测试覆盖：配置加载与配额规则、路径清洗、命名模板、SQLite 存储（记录 / 水位线 / 配额账本 / ID 列表）、
账号池轮换、限流、GraphQL 客户端（GET/POST、401/404/429、重试、畸形响应）、时间线解析（用户媒体 / 搜索 / 评论区
三种响应形状与新旧作者字段）、下载器（`.part` 续传、0 字节护栏）、五类任务、报告生成、操作台接口、queryId 刷新。

## 常见问题

| 现象 | 原因与处理 |
|---|---|
| 任务返回 **HTTP 404 且响应体为空** | queryId 过期：`java -jar $JAR refresh-operations`（可先 `--dry-run` 预览） |
| **403 + 空响应体** | X 开始要求 `x-client-transaction-id`（本工具未接生成器；详见「已知限制」） |
| **401** | Cookie 失效：重新取 `auth_token` 与 `ct0`（两者缺一不可） |
| **429** | 触发限流：多配几个账号自动轮换，或调大 `rate-limit.request-interval-ms` |
| 第二次运行「什么都没下」 | 正常：增量水位线 + 稳定键去重生效；要重扫加 `--fresh` |
| 构建报错但代码能跑 | 多半是**旧 jar** 或**操作台进程锁住了 jar**（Windows）：先停进程再 `clean package` |
| 中文乱码（日志 / 输出） | 加 `-Dfile.encoding=UTF-8` 启动 |

## 已知限制

1. **`x-client-transaction-id`（TxId）未接生成器**。代码已预留接口并标注了哪些 operation「理论上」需要它；
   实测当前 `SearchTimeline` / `TweetDetail` / `UserMedia` **不带该头也能正常返回**，故暂未实现。
   若将来 X 收紧校验，表现为 **403 + 空响应体**。
2. 操作台为**单任务串行**；「停止」是中断语义，不是优雅取消。
3. `download_record` 只登记**媒体**：`profile` 的 `description.txt`、`text` 任务的文本计入运行结果的成功数，但不生成媒体记录。
4. ID 列表的**校验也要发真实请求**（每条约 1 次），所以校验与下载串行；
   只有「用户不存在 / 已停用」才判为无效，**网络抖动不会**把有效 ID 误判为无效（只写 `last_error`）。
5. 未做：Cookie 自动刷新、定时调度、代理池自动健康检查。

## 许可证与致谢

- **许可证**：MIT
- **致谢**：接口调用参数（queryId / features / 请求头）参考自
  [caolvchong-top/twitter_download](https://github.com/caolvchong-top/twitter_download)（MIT）；
  本项目为 Java 重写与扩展。X / Twitter 是 X Corp. 的商标，本项目与其无任何关联。

> 使用本工具产生的一切后果由使用者自行承担；请务必遵守目标平台的服务条款与所在地法律法规，
> 不要用于批量抓取、侵权传播或任何商业化分发。
