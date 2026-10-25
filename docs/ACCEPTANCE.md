# aster-agent MCP 工具箱 · 验收手册（2026-10-26）

> 目标：5 分钟内完成「服务在跑 → 20 个工具可见 → 六组能力各验一发 → 与 yu-ai-agent 联调」。
> 当前状态：已通过 launchctl 常驻部署（`com.zwx.aster-agent-mcp`），日志 `/tmp/aster-agent-mcp.log`。

## 1. 服务存活（30 秒）

```sh
curl http://127.0.0.1:8124/actuator/health     # 预期 {"status":"UP"}
open http://127.0.0.1:8124/                     # 浏览器打开工具目录首页：20 个工具 + 配置状态一目了然
```

若服务未跑：`cd ~/Desktop/agent/aster-mcp && ./scripts/launchctl.sh install`（或 `restart`）。

## 2. 协议与工具目录（1 分钟）

```sh
./scripts/verify-sse.sh
# 预期：initialize/initialized/tools/list 均 HTTP 200，SSE 流返回 serverInfo "aster-agent"，工具数量 20
```

stdio 冒烟（18 项断言全绿为通过，含真实网络请求 github/web_fetch/headless Chrome）：

```sh
./scripts/smoke-test.sh     # 预期：18 通过 / 0 警告 / 0 失败
```

## 3. 六组能力逐个体验（2 分钟）

在 MCP 客户端（或下面的 curl 直调）里调用。直调模板（SSE 会话）：

```sh
# 先开一个终端保持 SSE：
curl -sN http://127.0.0.1:8124/sse &
# 另一个终端（endpoint 从上面 SSE 输出里复制）：
curl -X POST 'http://127.0.0.1:8124/sse/message?sessionId=<上面输出的sessionId>' \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"t","version":"0"}}}'
curl -X POST '<同上URL>' -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'
curl -X POST '<同上URL>' -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"browser_navigate","arguments":{"url":"https://example.com"}}}'
```

| 验收点 | 调用 | 预期 |
|---|---|---|
| 自描述 | `toolbox_catalog` | 返回 7 组清单与各依赖状态 |
| 搜索 | `web_search` {"query":"今天上海天气"} | 配了 key → searchapi(baidu) 结果；未配 → 明确降级/指引 |
| 抓取 | `web_fetch` {"url":"https://example.com"} | 干净 Markdown，带来源行 |
| 文档 | `docs_read` {"library":"spring-ai","topic":"tool"} | Context7 最新文档片段（不可达时自动降级官方站抓取） |
| 数据库 | `db_query` {"sql":"SELECT 1"} | 未配 ASTER_DB_URL → 返回配置指引；配上后正常出 `rowCount/rows`；试 `db_query {"sql":"DELETE FROM x"}` 应被只读护栏拒绝 |
| 文件 | `fs_list` / `fs_read welcome.md` / `fs_write x.txt` | 列表/读取正常；写被只读模式拦截并给出开启方法 |
| GitHub | `github_search_repos` {"query":"mcp server","limit":3} | 仓库清单（星数/语言） |
| 浏览器 | `browser_navigate` {"url":"https://example.com"} | headless Chrome 渲染后的可见文本（"Example Domain"） |
| 截图 | `browser_screenshot` {"url":"https://example.com"} | 返回沙箱 screenshots/ 下 PNG 路径（可直接 open） |
| 知识库 | `kb_documents` / `kb_retrieval` | 主服务在线且配 token → 文档清单/真实 RAG 命中；未配 → 401/403 指引 |

审计验证：`tail -5 aster-agent/logs/audit.jsonl` —— 每次调用都有 时间/工具/耗时/状态/规模 记录。

## 4. 与 yu-ai-agent 主项目联调（1 分钟，可选）

1. **放行私网 MCP 地址**：主项目默认 `app.mcp.allow-private-endpoints=false` 会拒绝 `127.0.0.1` 的 endpoint。
   在 `yu-ai-agent/src/main/resources/application-local.yml` 增量加：
   ```yaml
   app:
     mcp:
       allow-private-endpoints: true
   ```
   （该文件为用户托管，请自行添加；改完重启主服务。）
2. 管理端「MCP 服务」新增：名称 `aster-toolbox`，endpoint `http://127.0.0.1:8124/sse`，认证 NONE。
3. 点「测试连接」→ 预期"已连接并发现 20 个工具"；在 allowed_tools 里按需勾选（如 `web_search`、`browser_navigate`、`kb_retrieval`）。
4. 对话里让 agent 用工具（如"用工具箱搜一下 X"），SSE 正常出结果即联调成功。

## 5. 增强配置（可选，30 秒/项）

| 想要的能力 | 操作 |
|---|---|
| 稳定的联网搜索 | `launchctl` 场景：编辑 `scripts/launchctl.sh` 的 java 命令前加 `SEARCH_API_API_KEY=与主项目同款key`（或改用 setenv 的 plist）；然后 restart |
| db 组直连主库 | `ASTER_DB_URL=jdbc:postgresql://127.0.0.1:5432/<库名> ASTER_DB_USER=... ASTER_DB_PASSWORD=...` |
| kb 组免 401 | `ASTER_AGENT_TOKEN=<主服务 /api/auth/login 拿到的 token>`（账号需知识库权限） |
| GitHub 提额 | `GITHUB_TOKEN=<classic token>` |

改完执行 `./scripts/launchctl.sh restart`，`toolbox_catalog` 会回报最新状态。

## 6. 故障排查

| 现象 | 处置 |
|---|---|
| 8124 未监听 | `./scripts/launchctl.sh log` 看启动日志；`restart`；确认 jar 已构建 |
| SSE 握手不通 | 确认走的顺序：initialize → **notifications/initialized** → 请求（SDK 会话状态机要求） |
| web_search 报降级不可达 | 正常（无 key + 国内网络），配 SEARCH_API_API_KEY 即可 |
| browser 组报未找到 Chrome | 装 Chrome 或 `CHROME_PATH` 指定；容器部署内无浏览器属预期 |
| kb 组 401/403 | 见 5（token/角色）；WORKFLOW 账号可用 agentKey ∈ {test, super} |
| 主项目"测试连接"失败 | 九成是没开 `allow-private-endpoints`；其次是填成了 `/` 而非 `/sse` |
