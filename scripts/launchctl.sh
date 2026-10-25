#!/usr/bin/env bash
# macOS launchctl 常驻部署（对齐主仓库 AGENTS.md 的 com.zwx.* 惯例）
# 用法：scripts/launchctl.sh [install|start|stop|restart|status|log]
set -eo pipefail
LABEL="com.zwx.aster-agent-mcp"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="/tmp/aster-agent-mcp.log"
PLIST_LABEL="gui/$(id -u)/$LABEL"

remove() { launchctl remove "$LABEL" 2>/dev/null || true; }

case "${1:-install}" in
  install)
    remove
    launchctl submit -l "$LABEL" -- /bin/zsh -lc \
      "cd '$ROOT/aster-agent' && exec /usr/bin/java -jar target/aster-agent-mcp-server-0.0.1-SNAPSHOT.jar > '$LOG' 2>&1"
    echo "✅ 已安装并启动 $LABEL（日志：$LOG）"
    ;;
  start)   launchctl kickstart "$PLIST_LABEL" && echo "已启动" ;;
  stop)    launchctl kill SIGTERM "$PLIST_LABEL" && echo "已停止" ;;
  restart) launchctl kickstart -k "$PLIST_LABEL" && echo "已重启" ;;
  status)  launchctl list | grep "$LABEL" || echo "未注册" ;;
  log)     tail -f "$LOG" ;;
  *) echo "用法：$0 [install|start|stop|restart|status|log]"; exit 1 ;;
esac
