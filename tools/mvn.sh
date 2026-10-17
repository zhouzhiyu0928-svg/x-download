#!/usr/bin/env bash
# 本工程专用 Maven 包装：本机 PATH 上的 `mvn` 坏了（classworlds ClassNotFoundException），
# 故直接用 java 调 Launcher。代理仅在 127.0.0.1:10808 可连时才注入（Maven 不读 shell 的 *_proxy）。
# 用法： bash tools/mvn.sh -q test
set -euo pipefail
MVN_HOME="${MVN_HOME:-/g/maven/apache-maven-3.9.9}"
MVN_HOME_WIN="$(cygpath -w "$MVN_HOME" 2>/dev/null || echo 'G:\maven\apache-maven-3.9.9')"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_DIR_WIN="$(cygpath -w "$PROJECT_DIR")"

PROXY_FLAGS=()
if (echo > /dev/tcp/127.0.0.1/10808) >/dev/null 2>&1; then
  PROXY_FLAGS=(-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=10808 \
               -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=10808 \
               -Dhttp.nonProxyHosts=localhost\|127.0.0.1)
fi

exec java \
  -Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8 \
  "${PROXY_FLAGS[@]}" \
  -classpath "${MVN_HOME_WIN}\\boot\\plexus-classworlds-2.8.0.jar" \
  -Dclassworlds.conf="${MVN_HOME_WIN}\\bin\\m2.conf" \
  -Dmaven.home="${MVN_HOME_WIN}" \
  -Dmaven.multiModuleProjectDirectory="${PROJECT_DIR_WIN}" \
  org.codehaus.plexus.classworlds.launcher.Launcher "$@"
