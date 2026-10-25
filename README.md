# aster-mcp 工作区

**aster-agent 独立 MCP 工具箱服务**：把市面上最热门的五类 MCP 能力（联网搜索与抓取 / 最新库文档 / 数据库查询 / 文件与 GitHub / 浏览器自动化）与 aster 业务知识库工具，收敛为一个**可单独部署**的 MCP Server，供 yu-ai-agent、Claude Desktop、Cursor 等任意 MCP 客户端接入。

- 服务名：`aster-agent`（沿用主项目 agent 名）· 端口 `8124`（SSE）· Java 21 / Spring Boot 3.4.5 / Spring AI 1.1.8（MCP SDK 0.18.3）
- 明早验收直接看 **[docs/ACCEPTANCE.md](docs/ACCEPTANCE.md)**（5 分钟路径，含与 yu-ai-agent 的联调）

## 目录结构

```
aster-mcp/
├── aster-agent/                  # MCP 服务本体（独立 Maven 工程，可单独构建部署）
│   ├── pom.xml                   # artifactId: aster-agent-mcp-server
│   ├── Dockerfile
│   └── src/main/java/com/zwx/asteragent/
│       ├── AsterAgentMcpServerApplication.java
│       ├── ToolboxProperties.java     # aster.* 配置绑定（全部 env 可覆盖）
│       ├── ToolboxConfig.java         # 按组装配 + 审计装饰
│       ├── core/                      # 护栏与设施：UrlGuard/SqlGuard/FsSandbox/ToolAudit/...
│       ├── catalog/                   # GET / 工具目录首页
│       └── tools/                     # 六组工具 + toolbox_catalog 元工具
├── docs/
│   ├── 2026-10-26-aster-agent-mcp-design.md   # 设计文档（决策与边界）
│   ├── TEST-REPORT-2026-10-26.md              # 测试报告（四层验证 + 缺陷修复记录）
│   └── ACCEPTANCE.md                          # 验收手册
├── scripts/
│   ├── build.sh                  # 构建（含单测）
│   ├── run.sh                    # 前台运行（SSE :8124）
│   ├── smoke-test.sh             # stdio 模式全工具冒烟（18 项断言）
│   ├── verify-sse.sh             # SSE 客户端握手验证
│   └── launchctl.sh              # macOS 常驻部署（install/start/stop/restart/status/log）
├── docker-compose.yml
└── README.md
```

## 工具目录（20 个）

| 组 | 工具 | 对应市面热门能力 | 免配置可用 |
|---|---|---|---|
| meta | `toolbox_catalog` | 自描述目录（接入后先调它） | ✅ |
| web | `web_search` / `web_fetch` / `web_extract_links` | Exa / Firecrawl（搜索与抓取） | fetch/links ✅；search 需 `SEARCH_API_API_KEY`（与主项目同款），无 key 自动降级 DuckDuckGo |
| docs | `docs_search` / `docs_read` | Context7（最新库文档，防 API 幻觉） | ✅（Context7 不可达时降级官方文档站抓取） |
| db | `db_schema` / `db_query` | Postgres / Supabase | 需 `ASTER_DB_URL/USER/PASSWORD`（只读护栏：单语句/禁写/自动 LIMIT/超时） |
| fs | `fs_list` / `fs_read` / `fs_write` | Filesystem | ✅（沙箱根 jail；默认只读，`ASTER_FS_ALLOW_WRITE=true` 开写） |
| github | `github_search_repos` / `github_get_file` / `github_list_issues` / `github_list_pulls` | GitHub | ✅（匿名 60 次/时；`GITHUB_TOKEN` 提至 5000 次/时） |
| browser | `browser_navigate` / `browser_eval` / `browser_screenshot` | Playwright / Chrome DevTools | ✅（纯 JDK WebSocket 直连本机 headless Chrome，零 Node 依赖） |
| kb | `kb_documents` / `kb_retrieval` | **aster 业务契合**：查 yu-ai-agent 知识库清单 + 真实 RAG 检索验证 | 需主服务在线（`ASTER_AGENT_TOKEN` 可选） |

## 快速开始

```sh
# 1. 构建（含单测）
./scripts/build.sh

# 2. 前台启动（或 scripts/launchctl.sh install 常驻）
./scripts/run.sh

# 3. 验证
curl http://127.0.0.1:8124/actuator/health     # {"status":"UP"}
./scripts/verify-sse.sh                         # SSE 完整握手 + 工具列表
./scripts/smoke-test.sh                         # stdio 全工具冒烟
open http://127.0.0.1:8124/                     # 工具目录首页
```

## 客户端接入

**yu-ai-agent（主项目）**：管理端「MCP 服务」新增，endpoint 填 `http://127.0.0.1:8124/sse`。
⚠️ 主项目默认拒绝私网 MCP 地址，需在 application-local.yml 设 `app.mcp.allow-private-endpoints: true`（详见 ACCEPTANCE.md）。

**Claude Desktop / Cursor 等**：

```json
{ "mcpServers": { "aster-agent": { "url": "http://127.0.0.1:8124/sse" } } }
```

**stdio 模式**（与主项目 `services/*-mcp` 同款用法，注意一问一答调用）：

```sh
java -Dspring.ai.mcp.server.stdio=true -Dspring.main.web-application-type=none \
     -Dlogging.pattern.console= -jar aster-agent/target/aster-agent-mcp-server-0.0.1-SNAPSHOT.jar
```

## 配置（环境变量 → 能力）

| 变量 | 作用 | 默认 |
|---|---|---|
| `SEARCH_API_API_KEY` / `SEARCH_API_PROVIDER` / `ASTER_WEB_SEARCH_ENGINE` | web_search（与主项目同源 key；provider=searchapi/tavily；engine=baidu/google） | 空 / searchapi / baidu |
| `ASTER_DB_URL` / `ASTER_DB_USER` / `ASTER_DB_PASSWORD` | db 组只读数据源（如 `jdbc:postgresql://127.0.0.1:5432/yu_ai_agent`） | 空（工具返回指引） |
| `GITHUB_TOKEN` | github 组限流提升 | 空（匿名） |
| `ASTER_FS_ROOT` / `ASTER_FS_ALLOW_WRITE` | fs 沙箱根 / 写开关 | `./sandbox` / 只读 |
| `ASTER_AGENT_BASE_URL` / `ASTER_AGENT_TOKEN` / `ASTER_AGENT_KEY` | kb 组主服务地址 / 登录态 / 智能体 | `http://127.0.0.1:8123/api` / 空 / test |
| `ASTER_ALLOW_PRIVATE` / `ASTER_URL_ALLOW_HOSTS` | SSRF 护栏放行开关 / 主机后缀白名单 | false / 空 |
| `CHROME_PATH` | 指定浏览器可执行文件 | 自动探测 Chrome/Edge/Chromium |

## 安全模型

- **SSRF 护栏**：web/docs/browser 组的任意 URL 校验——仅 http/https；拒绝环回/链路本地/RFC1918/CGNAT（字面 IP 额外拦 ULA）；域名解析路径放行 ULA 以兼容本机 fake-IP DNS（见设计文档 §4）。
- **SQL 护栏**：单语句、SELECT/WITH/EXPLAIN/SHOW/TABLE 白名单、全文禁写关键字（含 CTE 内）、无 LIMIT 自动包层、语句超时与行数上限。
- **文件沙箱**：所有 fs 路径强制限制在沙箱根内（路径穿越 + 符号链接双重校验），读写大小上限，默认只读。
- **审计**：每次 tools/call 记录 JSONL（时间/工具/入参摘要/耗时/状态/规模），见 `aster-agent/logs/audit.jsonl`。

## 已知边界

1. stdio 传输按 MCP 惯例**一问一答**；客户端并发灌多条请求时该传输存在响应竞态（SDK stdio 实现限制），SSE 模式无此问题（已实测 9 连发）。
2. web_search 无 key 时降级 DuckDuckGo，国内网络可能不可达——工具会返回带指引的明确错误；配 `SEARCH_API_API_KEY` 后恢复 searchapi（baidu 引擎）。
3. browser 组依赖本机 Chrome/Edge/Chromium；容器部署时无浏览器，browser 工具返回指引（其余组不受影响）。
4. kb 组接口需要主服务登录态与知识库权限（WORKFLOW/REVIEWER/ADMIN），未配置 token 时工具返回 401/403 指引。

更多背景与取舍见 [docs/2026-10-26-aster-agent-mcp-design.md](docs/2026-10-26-aster-agent-mcp-design.md)。
