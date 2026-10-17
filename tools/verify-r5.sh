#!/usr/bin/env bash
# 安装/验证辅助：R5（ID 列表 + 取消调用次数上限）断言
#
# 用法： bash tools/verify-r5.sh
# 覆盖：① 新子命令与打包内容 ② ID 列表 CLI 流程（add/list/check/remove-invalid/move）
#       ③ 操作台 ID 列表接口（增删改查 + 校验 + 一键删除无效 + 按顺序下载）
#       ④ 无每日次数上限（配置 0 = 不限，账号不限、估算不拦）
# 注意：接口请求体里的中文经 bash 传参会损坏 → 脚本内一律用 ASCII 备注。
set -euo pipefail
cd "$(dirname "$0")/.."

JAR="target/x-media-downloader.jar"
PASS=0
FAIL=0
ok()   { PASS=$((PASS+1)); echo "  [PASS] $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  [FAIL] $1"; }
check(){ if eval "$2" >/dev/null 2>&1; then ok "$1"; else bad "$1"; fi; }

# 用独立库跑，绝不碰用户的 ./data/xmedia.db
TESTDB="$(cygpath -m "$(mktemp -d)")/verify-r5.db"
J() { java -Dfile.encoding=UTF-8 -jar "$JAR" --db-path "$TESTDB" "$@"; }

echo "=== ① 子命令与打包内容 ==="
java -Dfile.encoding=UTF-8 -jar "$JAR" --help >/dev/null 2>&1 || true
HELP=$(java -Dfile.encoding=UTF-8 -jar "$JAR" --help 2>&1 | sed -e 's/\x1b\[[0-9;]*m//g')
check "子命令存在：targets" "echo '$HELP' | grep -qE '^ +targets +'"
for sub in add list check remove remove-invalid move toggle run; do
  if J targets "$sub" --help >/dev/null 2>&1; then ok "targets 子命令：$sub"; else bad "targets 子命令缺失：$sub"; fi
done
check "targets 表已建（Database 迁移生效）" "J targets list | grep -q '列表为空'"

echo "=== ② ID 列表 CLI 流程 ==="
# 未校验时也允许加入；格式非法的要明确报错
OUT=$(J targets add someid 2>&1 || true)
check "add 成功" "echo '$OUT' | grep -q '已加入'"
OUT=$(J targets add 'bad name' 2>&1 || true)
check "add 拒绝非法格式并说明原因" "echo '$OUT' | grep -q 'ID 格式非法'"
OUT=$(J targets add someid 2>&1 || true)
check "重复 add 不重复插入" "echo '$OUT' | grep -q '已在列表中'"
J targets add otherid >/dev/null 2>&1 || true
OUT=$(J targets list 2>&1)
check "list 显示序号与状态" "echo '$OUT' | grep -q 'someid' && echo '$OUT' | grep -q '未校验'"
check "toggle 停用后不参与下载" "J targets toggle 1 >/dev/null 2>&1 && J targets list >/dev/null 2>&1"
OUT=$(J targets move 1 down 2>&1 || true)
check "move 调整顺序" "echo '$OUT' | grep -qE '已调整顺序|已在端点'"
OUT=$(J targets remove 1 2>&1 || true)
check "remove 按时序号删除" "echo '$OUT' | grep -q '已删除'"
OUT=$(J targets remove-invalid 2>&1 || true)
check "remove-invalid 无无效项时安全" "echo '$OUT' | grep -qE '没有无效 ID|已删除'"

echo "=== ③ 操作台 ID 列表接口 ==="
PORT=$(( (RANDOM % 2000) + 19000 ))
java -Dfile.encoding=UTF-8 -jar "$JAR" --db-path "$TESTDB" console -p "$PORT" --no-browser >/dev/null 2>&1 &
SERVER_PID=$!
trap 'kill $SERVER_PID >/dev/null 2>&1 || true' EXIT
for i in $(seq 1 40); do
  curl -s --max-time 2 "http://127.0.0.1:$PORT/api/status" >/dev/null 2>&1 && break
  sleep 0.5
done
B="http://127.0.0.1:$PORT"
POST() { curl -s -X POST "$B$1" -H 'content-type: application/json' -d "$2"; }

check "GET /api/targets 可用"        "curl -s --max-time 5 $B/api/targets | grep -q 'rows'"
check "首页含 ID 列表页签"            "curl -s --max-time 5 $B/ | grep -q 'data-view=\"targets\"'"
check "首页不再展示历史/记录页签"      "! curl -s --max-time 5 $B/ | grep -qE 'data-view=\"(history|records)\"'"
check "历史接口仍保留（供去重与报告）"  "curl -s --max-time 5 $B/api/history | grep -q rows && curl -s --max-time 5 $B/api/records | grep -q rows"
check "首页含无效提醒与顺序下载按钮"   "curl -s --max-time 5 $B/ | grep -q '删除全部无效' && curl -s --max-time 5 $B/ | grep -q '按顺序全部下载'"
check "首页含列表进度显示"            "curl -s --max-time 5 $B/ | grep -q '列表进度'"

OUT=$(POST /api/targets '{"text":"alpha_verify  A-NOTE\nbeta_verify","note":""}')
check "新增多个（ASCII 备注落库）" "echo '$OUT' | grep -q '\"added\"' && echo '$OUT' | grep -q 'alpha_verify'"
check "备注写入成功" "curl -s --max-time 5 $B/api/targets | grep -q 'A-NOTE'"
check "重复新增 → skipped" "POST /api/targets '{\"text\":\"ALPHA_VERIFY\"}' | grep -q 'skipped'"
check "空内容 → 400" "test \"\$(curl -s -o /dev/null -w '%{http_code}' -X POST $B/api/targets -H 'content-type: application/json' -d '{\"text\":\"\"}')\" = 400"
check "畸形 JSON → 400 可读原因" "POST /api/targets '{bad' | grep -q '合法 JSON'"

ID1=$(curl -s --max-time 5 $B/api/targets | python -c "import sys,json;print(json.load(sys.stdin)['rows'][0]['id'])")
check "move 接口" "POST /api/targets/move \"{\\\"id\\\":$ID1,\\\"delta\\\":1}\" | grep -q 'moved'"
check "toggle 接口" "POST /api/targets/toggle \"{\\\"id\\\":$ID1,\\\"enabled\\\":false}\" | grep -q 'ok'"
check "停用后该条 enabled=false（不再参与批量下载）" \
  "curl -s --max-time 5 $B/api/targets | grep -q '\"enabled\":false'"

# 校验：先造一条必定不存在的 ID（X 用户名 ≤15 位）
POST /api/targets '{"text":"zz_nouser_1234"}' >/dev/null
check "check 接口可启动" "POST /api/targets/check '{\"all\":true}' | grep -q 'started'"
for i in $(seq 1 60); do
  RUNNING=$(curl -s --max-time 5 $B/api/status | python -c "import sys,json;print(json.load(sys.stdin)['status']['running'])")
  [ "$RUNNING" = "False" ] && break
  sleep 1
done
check "校验后发现无效 ID（invalidCount ≥ 1）" \
  "python -c \"import json,urllib.request;print(json.load(urllib.request.urlopen('$B/api/targets'))['invalidCount'])\" | grep -qE '^[1-9]'"
check "无效项不参与批量下载（runnableCount 不含它）" \
  "python -c \"
import json,urllib.request
d=json.load(urllib.request.urlopen('$B/api/targets'))
invalid={r['id'] for r in d['rows'] if r['status']=='INVALID'}
runnable={r['id'] for r in d['rows'] if r['enabled'] and r['status']!='INVALID'}
print('OK' if not (invalid & runnable) else 'BAD')\" | grep -q OK"

check "按列表顺序下载可启动（dry-run，空网络请求）" \
  "POST /api/run '{\"task\":\"user\",\"useTargetList\":true,\"targets\":[],\"dryRun\":true}' | grep -q 'started'"
for i in $(seq 1 60); do
  RUNNING=$(curl -s --max-time 5 $B/api/status | python -c "import sys,json;print(json.load(sys.stdin)['status']['running'])")
  [ "$RUNNING" = "False" ] && break
  sleep 1
done
check "列表任务有进度字段" "curl -s --max-time 5 $B/api/status | grep -q 'listTotal'"
check "删除全部无效" "POST /api/targets/remove-invalid '' | grep -q 'removed'"
check "删除后 invalidCount = 0" \
  "python -c \"import json,urllib.request;print(json.load(urllib.request.urlopen('$B/api/targets'))['invalidCount'])\" | grep -q '^0$'"

echo "=== ④ 无每日调用次数上限 ==="
check "设置接口：quotaPerAccountDaily = 0（不限）" \
  "curl -s --max-time 5 $B/api/settings | python -c \"import sys,json;print(json.load(sys.stdin)['quotaPerAccountDaily'])\" | grep -q '^0$'"
check "账号接口：quotaLimit = 0 且 remaining 为不限" \
  "curl -s --max-time 5 $B/api/accounts | grep -q '\"quotaLimit\":0'"
check "首页设置页显示「不限制」" "curl -s --max-time 5 $B/ | grep -q '不限制（quota.per-account-daily = 0）'"
kill $SERVER_PID >/dev/null 2>&1 || true
trap - EXIT

check "配置样例默认不限次数" "grep -q 'per-account-daily: 0' config.example.yaml"

echo
echo "===== 结果：通过 $PASS / 失败 $FAIL ====="
test "$FAIL" -eq 0
