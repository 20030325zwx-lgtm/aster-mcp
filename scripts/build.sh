#!/usr/bin/env bash
# 构建 aster-agent MCP 服务（含单测）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/aster-agent"
mvn package "$@"
echo "✅ 构建完成：aster-agent/target/aster-agent-mcp-server-0.0.1-SNAPSHOT.jar"
