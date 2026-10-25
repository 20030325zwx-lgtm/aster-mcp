#!/usr/bin/env bash
# aster-agent MCP 服务 stdio 模式冒烟测试：
# 标准 JSON-RPC 握手（initialize → initialized）后逐条调用工具并等待响应
# （stdio 传输按 MCP 惯例一问一答；SSE 模式支持并发，见 verify-sse.sh）。
# 用法：scripts/smoke-test.sh   （依赖已构建的 jar，先跑 scripts/build.sh）
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/aster-agent/target/aster-agent-mcp-server-0.0.1-SNAPSHOT.jar"
if [ ! -f "$JAR" ]; then
  echo "❌ 未找到 jar，先执行 scripts/build.sh"; exit 1
fi

# 沙箱里放一个已知文件供 fs_read 断言
mkdir -p "$ROOT/aster-agent/sandbox"
echo "aster-agent toolbox smoke file" > "$ROOT/aster-agent/sandbox/welcome.md"

OUT="$(mktemp /tmp/aster-smoke-out.XXXXXX)"
ERR="$(mktemp /tmp/aster-smoke-err.XXXXXX)"
FIFO="$(mktemp -u /tmp/aster-smoke-fifo.XXXXXX)"
mkfifo "$FIFO"

cd "$ROOT/aster-agent"
java -Dspring.ai.mcp.server.stdio=true -Dspring.main.web-application-type=none \
     -Dlogging.pattern.console= -Dspring.main.banner-mode=off -jar "$JAR" < "$FIFO" > "$OUT" 2>"$ERR" &
PID=$!
exec 3>"$FIFO"   # 保持 stdin 打开（EOF 会让 stdio 传输提前关闭）

resp_has() { grep "\"id\":$1" "$OUT" | grep -q -- "$2"; }
send_wait() { # <json> <id> [超时秒数=40]
  printf '%s\n' "$1" >&3
  local id="$2" budget="${3:-40}" waited=0
  until grep -q "\"id\":$id" "$OUT" 2>/dev/null; do
    sleep 1; waited=$((waited+1))
    [ $waited -ge $budget ] && echo "  （id=$id 等待 ${budget}s 超时，继续）" && return 1
  done
  return 0
}

send_wait '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"aster-smoke","version":"0.0.1"}}}' 1 30 || true
printf '%s\n' '{"jsonrpc":"2.0","method":"notifications/initialized"}' >&3
sleep 1
send_wait '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' 2 || true
send_wait '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"toolbox_catalog","arguments":{}}}' 3 || true
send_wait '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"fs_list","arguments":{"path":"."}}}' 4 || true
send_wait '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"fs_read","arguments":{"path":"welcome.md"}}}' 5 || true
send_wait '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"fs_write","arguments":{"path":"x.txt","content":"hi"}}}' 6 || true
send_wait '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"db_query","arguments":{"sql":"SELECT 1"}}}' 7 || true
send_wait '{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"github_search_repos","arguments":{"query":"mcp server","limit":3}}}' 8 || true
send_wait '{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"web_fetch","arguments":{"url":"https://example.com"}}}' 9 || true
send_wait '{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"browser_navigate","arguments":{"url":"https://example.com"}}}' 10 60 || true
send_wait '{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"kb_documents","arguments":{}}}' 11 || true

exec 3>&-
kill $PID 2>/dev/null; wait $PID 2>/dev/null

pass=0; fail=0; warn=0
check() { # <名称> <命令...>  —— 失败即 FAIL
  local name="$1"; shift
  if "$@"; then echo "  ✅ $name"; pass=$((pass+1));
  else echo "  ❌ $name"; fail=$((fail+1)); fi
}
check_soft() { # 网络相关，失败仅 WARN
  local name="$1"; shift
  if "$@"; then echo "  ✅ $name"; pass=$((pass+1));
  else echo "  ⚠️  $name（网络/环境相关，不算失败）"; warn=$((warn+1)); fi
}

echo "── aster-agent MCP stdio 冒烟 ──"
check "initialize 握手返回 serverInfo"        grep -q '"serverInfo"' "$OUT"
check "serverInfo.name = aster-agent"          grep -q '"name":"aster-agent"' "$OUT"
check "tools/list 返回 web_search"             resp_has 2 '"web_search"'
check "tools/list 返回 docs_read"              resp_has 2 '"docs_read"'
check "tools/list 返回 db_query"               resp_has 2 '"db_query"'
check "tools/list 返回 fs_write"               resp_has 2 '"fs_write"'
check "tools/list 返回 github_get_file"        resp_has 2 '"github_get_file"'
check "tools/list 返回 browser_screenshot"     resp_has 2 '"browser_screenshot"'
check "tools/list 返回 kb_retrieval"           resp_has 2 '"kb_retrieval"'
check "toolbox_catalog 含组信息"               resp_has 3 '联网搜索与网页抓取'
check "fs_list 列出 welcome.md"                resp_has 4 'welcome.md'
check "fs_read 读出内容"                       resp_has 5 'smoke file'
check "fs_write 只读模式被拦（TOOL_ERROR）"    resp_has 6 '只读'
check "db_query 未配置时给指引"                resp_has 7 'ASTER_DB_URL'
check_soft "github_search_repos 有结果"        resp_has 8 'fullName'
check_soft "web_fetch 抓到 example.com"        resp_has 9 'Example Domain'
check_soft "browser_navigate 渲染成功"         resp_has 10 'Example Domain'
check_soft "kb_documents 有响应"               resp_has 11 'result\|TOOL_ERROR'

echo "── 结果：$pass 通过 / $warn 警告 / $fail 失败 ──"
if [ $fail -gt 0 ]; then
  echo "原始输出：$OUT（错误日志：$ERR）"; exit 1
fi
echo "完整输出保留在：$OUT"
