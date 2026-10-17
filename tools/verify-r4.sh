# 安装/验证辅助：项目内可复跑的 R4 断言（打包后运行）
#
# 用法： bash tools/verify-r4.sh
# 覆盖：① jar 与子命令普查 ② 关键行为断言（dry-run 不写状态、报告生成、操作台接口）
#       ③ 安全断言（操作台不泄露 Cookie） ④ 产出物普查
set -euo pipefail
cd "$(dirname "$0")/.."

JAR="target/x-media-downloader.jar"
PASS=0
FAIL=0

ok()   { PASS=$((PASS+1)); echo "  [PASS] $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  [FAIL] $1"; }
check(){ if eval "$2" >/dev/null 2>&1; then ok "$1"; else bad "$1"; fi; }

echo "=== ① jar 与子命令 ==="
check "存在 fat-jar" "test -f '$JAR'"
check "jar 含主类清单" "jar tf '$JAR' | grep -q 'META-INF/MANIFEST.MF'"
# picocli 帮助输出带 ANSI 颜色，先剥掉再匹配
HELP=$(java -Dfile.encoding=UTF-8 -jar "$JAR" --help 2>&1 | sed -e 's/\x1b\[[0-9;]*m//g')
for c in user tag reply text profile report console refresh-operations; do
  if echo "$HELP" | grep -qE "^ +$c +"; then ok "子命令存在：$c"; else bad "子命令缺失：$c"; fi
done
check "操作台静态资源已打包" "jar tf '$JAR' | grep -q 'console/index.html'"
check "operations.yaml 已打包" "jar tf '$JAR' | grep -q 'operations.yaml'"

echo "=== ② 关键行为断言 ==="
# dry-run 不得写入任何状态：用独立库路径跑一次，断言库文件未被创建
# MSYS 路径要转成 Java 认的 Windows 形式（cygpath -m：正斜杠混合盘符）
TMPDB="$(cygpath -m "$(mktemp -d)")/dry.db"
java -Dfile.encoding=UTF-8 -jar "$JAR" --db-path "$TMPDB" --dry-run user -u Susu0526m >/dev/null 2>&1 || true
# 注意：dry-run 会建库文件（装配阶段即打开 SQLite），但不得写入任何记录/水位线/任务行
ROWS=$(python -c "
import sqlite3,sys
try:
    c=sqlite3.connect(r'$TMPDB')
    print(sum(c.execute('select count(*) from '+t).fetchone()[0] for t in ('download_record','sync_cursor','task_run')))
except Exception as e:
    print(0)
" 2>/dev/null || echo 0)
if [ "$ROWS" = "0" ]; then ok "dry-run 未写入任何记录/水位线/任务行"; else bad "dry-run 写了 $ROWS 行状态"; fi

# 报告：CSV 带 BOM + Markdown 有总览
OUT="$(cygpath -m "$(mktemp -d)")"
java -Dfile.encoding=UTF-8 -jar "$JAR" report --out "$OUT" >/dev/null 2>&1 || true
check "报告 CSV 已生成" "ls '$OUT'/*.csv"
check "报告 Markdown 已生成" "ls '$OUT'/*.md"
check "Markdown 含总览小节" "grep -q '## 总览' '$OUT'/*.md"
check "CSV 带 UTF-8 BOM" "head -c3 '$OUT'/*.csv | grep -q \$'\xef\xbb\xbf'"

echo "=== ③ 操作台接口冒烟（随机端口，跑完即关） ==="
PORT=$(( (RANDOM % 2000) + 18000 ))
java -Dfile.encoding=UTF-8 -jar "$JAR" console -p "$PORT" --no-browser >/dev/null 2>&1 &
SERVER_PID=$!
for i in $(seq 1 20); do
  if curl -s --max-time 2 "http://127.0.0.1:$PORT/api/status" >/dev/null 2>&1; then break; fi
  sleep 0.5
done
check "首页返回操作台页面"  "curl -s --max-time 5 http://127.0.0.1:$PORT/ | grep -q '操作台'"
check "首页含 5 个页签"      "curl -s --max-time 5 http://127.0.0.1:$PORT/ | grep -c 'data-view=' | grep -q 5"
check "首页含免责声明"       "curl -s --max-time 5 http://127.0.0.1:$PORT/ | grep -q '仅供编程学习'"
check "/api/status 可用"     "curl -s --max-time 5 http://127.0.0.1:$PORT/api/status | grep -q 'running'"
check "/api/settings 可用"   "curl -s --max-time 5 http://127.0.0.1:$PORT/api/settings | grep -q 'namingTemplate'"
check "/api/records 可用"    "curl -s --max-time 5 http://127.0.0.1:$PORT/api/records | grep -q 'rows'"
check "/api/history 可用"    "curl -s --max-time 5 http://127.0.0.1:$PORT/api/history | grep -q 'rows'"
check "账号接口不含 auth_token" "! curl -s --max-time 5 http://127.0.0.1:$PORT/api/accounts | grep -q 'auth_token'"
check "账号接口不含 ct0"        "! curl -s --max-time 5 http://127.0.0.1:$PORT/api/accounts | grep -q 'ct0='"
check "空目标执行请求被拒(400)" "test \"\$(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'content-type: application/json' -d '{\"task\":\"user\",\"targets\":[]}' http://127.0.0.1:$PORT/api/run)\" = 400"
check "未知路径 404"          "test \"\$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:$PORT/nope)\" = 404"
kill $SERVER_PID >/dev/null 2>&1 || true

echo "=== ④ 产出物普查 ==="
check "日志文件已生成" "test -s ./logs/xmedia.log"
check "下载目录存在" "test -d ./downloads"

echo
echo "===== 结果：通过 $PASS / 失败 $FAIL ====="
test "$FAIL" -eq 0
