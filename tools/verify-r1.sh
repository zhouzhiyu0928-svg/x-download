#!/usr/bin/env bash
# R1 落包与端到端断言：产物完整性 + 真实 jar 的 --dry-run/-help/退出码。
# 用法： bash tools/verify-r1.sh
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
PROJ="$(pwd)"
# Java / Windows-Python 都不认 MSYS 路径（/d/x down），统一用 cygpath -m 转成正斜杠 Windows 路径
PROJW="$(cygpath -m "$PROJ" 2>/dev/null || echo "$PROJ")"
JAR="$PROJ/target/x-media-downloader.jar"
JARW="$PROJW/target/x-media-downloader.jar"
PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "  [PASS] $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  [FAIL] $1"; }
check(){ if eval "$2" >/dev/null 2>&1; then ok "$1"; else bad "$1"; fi; }

echo "== 1. 产物存在与主类 =="
check "jar 存在且 > 5MB" "[ -f '$JAR' ] && [ \$(stat -c%s '$JAR') -gt 5000000 ]"
MANIFEST_MAIN="$(python -c "
import zipfile,sys
z=zipfile.ZipFile(r'$JARW')
m=z.read('META-INF/MANIFEST.MF').decode('utf-8','replace')
print([l.split(':',1)[1].strip() for l in m.splitlines() if l.startswith('Main-Class')][0])
" 2>/dev/null)"
if [ "$MANIFEST_MAIN" = "com.xmedia.downloader.Application" ]; then ok "Main-Class 正确 ($MANIFEST_MAIN)"; else bad "Main-Class 异常：$MANIFEST_MAIN"; fi

echo "== 2. 关键类与依赖已打入 fat-jar =="
MISSING="$(python - "$JARW" <<'PY'
import sys, zipfile
need = ["com/xmedia/downloader/Application.class",
        "com/xmedia/downloader/cli/MainCommand.class",
        "com/xmedia/downloader/config/ConfigLoader.class",
        "com/xmedia/downloader/util/PathSanitizer.class",
        "com/xmedia/downloader/util/NamingTemplate.class",
        "com/xmedia/downloader/storage/Database.class",
        "com/xmedia/downloader/storage/WriteQueue.class",
        "com/xmedia/downloader/account/AccountPool.class",
        "com/xmedia/downloader/ratelimit/QuotaLedger.class",
        "org/sqlite/JDBC.class"]
names = set(zipfile.ZipFile(sys.argv[1]).namelist())
print(",".join(n for n in need if n not in names))
PY
)"
if [ $? -ne 0 ]; then bad "jar 内容检查失败（python/路径问题）"
elif [ -z "$MISSING" ]; then ok "10 个关键类/驱动齐全"; else bad "缺失：$MISSING"; fi

echo "== 3. 真实 jar：--help / --dry-run / 退出码 =="
# 重定向到文件时 Java 17 按平台编码（本机 GBK）写中文 → 断言会失败，故强制 UTF-8
run_jar() { java -Dfile.encoding=UTF-8 -jar "$JARW" "$@"; }
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
# 用 cygpath -m（正斜杠 Windows 路径）：反斜杠在 YAML 双引号里是转义字符，会解析失败
TMPW="$(cygpath -m "$TMP" 2>/dev/null || echo "$TMP")"
cat > "$TMP/ok.yaml" <<EOF
save-path: "$TMPW/downloads"
db-path: "$TMPW/x.db"
log: {level: INFO, file: "$TMPW/x.log"}
accounts:
  - name: account1
    note: "本人账号"
    cookie: "auth_token=t; ct0=c;"
quota: {per-account-daily: 100, reserve-for-retry: 5, estimate-tweets-per-call: 19}
EOF
cat > "$TMP/bad.yaml" <<'EOF'
accounts:
  - name: a1
    cookie: "auth_token=t;"
EOF

run_jar --help > "$TMP/help.txt" 2>&1; H=$?
if [ $H -eq 0 ] && grep -q "x-media-downloader" "$TMP/help.txt"; then ok "--help 退出码 0 且有用法输出"; else bad "--help 异常（exit=$H）"; fi

run_jar --dry-run -c "$TMPW/ok.yaml" --tweets 100 > "$TMP/dry.txt" 2>&1; D=$?
if [ $D -eq 0 ]; then ok "--dry-run 退出码 0"; else bad "--dry-run 退出码 $D：$(tail -3 "$TMP/dry.txt")"; fi
check "dry-run 声明未发网络请求" "grep -q 'dry-run：未发起任何网络请求' '$TMP/dry.txt'"
check "数据库 journal_mode=wal" "grep -q 'wal' '$TMP/dry.txt'"
check "打印结构版本" "grep -q '结构版本' '$TMP/dry.txt'"
check "配额估算=充足" "grep -q '充足' '$TMP/dry.txt'"
check "落盘建库成功" "[ -s '$TMP/x.db' ]"

run_jar --dry-run -c "$TMPW/bad.yaml" > "$TMP/bad.txt" 2>&1; B=$?
if [ $B -eq 3 ] && grep -q '\[配置错误\]' "$TMP/bad.txt"; then ok "配置错误 → 退出码 3"; else bad "配置错误退出码应为 3，实际 $B"; fi

run_jar --dry-run -c "$TMPW/ok.yaml" --tweets 100000 > "$TMP/quota.txt" 2>&1; Q=$?
if [ $Q -eq 5 ] && grep -q '\[配额不足\]' "$TMP/quota.txt"; then ok "配额不足 → 退出码 5 且提示所需账号数"; else bad "配额不足退出码应为 5，实际 $Q"; fi

echo
echo "==== 结果：PASS=$PASS FAIL=$FAIL ===="
[ "$FAIL" -eq 0 ] || exit 1
