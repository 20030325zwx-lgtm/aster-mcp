#!/usr/bin/env bash
# SSE 传输联通性验证：模拟 MCP 客户端完整握手
# （GET /sse 拿 endpoint → POST initialize → POST notifications/initialized → tools/list）
set -uo pipefail
BASE="${ASTER_MCP_BASE:-http://127.0.0.1:8124}"
SSE_OUT="$(mktemp /tmp/aster-sse.XXXXXX)"

echo "→ 连接 SSE 端点 $BASE/sse ..."
curl -sN --max-time 25 "$BASE/sse" > "$SSE_OUT" &
CURL_PID=$!
sleep 2

# SSE 事件格式为 `event:endpoint`（冒号后无空格）
ENDPOINT="$(awk '/^event:endpoint/{f=1;next} f&&/^data:/{sub(/^data:/,"");print;exit}' "$SSE_OUT" | tr -d '\r')"
if [ -z "$ENDPOINT" ]; then
  echo "❌ 未收到 endpoint 事件"; kill $CURL_PID 2>/dev/null; exit 1
fi
case "$ENDPOINT" in
  http*) URL="$ENDPOINT" ;;
  /*)    URL="$BASE$ENDPOINT" ;;
  *)     URL="$BASE/$ENDPOINT" ;;
esac
echo "→ message endpoint: $URL"

post() { curl -s --max-time 10 -o /dev/null -w '%{http_code}' -X POST "$URL" -H 'Content-Type: application/json' -d "$1"; }
echo "→ initialize：HTTP $(post '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"aster-sse-check","version":"0.0.1"}}}')"
echo "→ notifications/initialized：HTTP $(post '{"jsonrpc":"2.0","method":"notifications/initialized"}')"
echo "→ tools/list：HTTP $(post '{"jsonrpc":"2.0","id":2,"method":"tools/list"}')"

sleep 3
kill $CURL_PID 2>/dev/null
if grep -q '"serverInfo"' "$SSE_OUT" && grep -q 'aster-agent' "$SSE_OUT" && grep -q '"tools"' "$SSE_OUT"; then
  echo "✅ SSE 会话完整：握手 + 工具列表均正常"
  grep -o '"name":"aster-agent"' "$SSE_OUT" | head -1
  echo "工具数量：$(grep -o '"name":"[a-z_]*"' "$SSE_OUT" | wc -l | tr -d ' ')"
  exit 0
else
  echo "❌ SSE 流响应不完整，输出如下："
  head -20 "$SSE_OUT"; exit 1
fi
