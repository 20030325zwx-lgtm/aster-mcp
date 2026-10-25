#!/usr/bin/env bash
# 前台启动 aster-agent MCP 服务（SSE 模式，:8124）
# 环境变量按需带上，例如：
#   SEARCH_API_API_KEY=xxx scripts/run.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/aster-agent"
exec java -jar target/aster-agent-mcp-server-0.0.1-SNAPSHOT.jar
