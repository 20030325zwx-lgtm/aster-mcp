# aster-agent MCP 工具箱 · 测试报告（2026-10-26）

> 测试执行时间：2026-10-26 01:40 – 02:20（本地全量重跑复验）
> 结论先行：**全部通过——单测 14/14，stdio 冒烟 18/18（0 警告），SSE 会话完整（握手 + 20 工具 + 并发 9 连发），部署终验（launchctl/health/首页/审计）全绿。** 测试期间发现并修复 5 项缺陷（含 1 项第三方 SDK 级阻塞问题），详见 §6。

## 1. 测试环境

| 项 | 值 |
|---|---|
| 机器 | macOS (darwin 25.6.0 arm64) |
| JDK / 构建 | Java 21 · Maven · spring-boot-starter-parent 3.4.5 |
| 关键依赖 | Spring AI 1.1.8（MCP SDK 0.18.3）、jsoup 1.18.3、HikariCP、PostgreSQL Driver |
| 部署形态 | launchctl 常驻 `com.zwx.aster-agent-mcp`（SSE :8124，日志 /tmp/aster-agent-mcp.log） |
| 网络环境 | 有代理（fake-IP DNS：公网域名解析为 fdfe:… ULA 段，影响 SSRF 护栏设计，见 §6.4） |
| 依赖服务 | 主项目 yu-ai-agent 在线（:8123，kb 组目标）；8127/8128 为旧图片/文档 MCP，端口无冲突 |

## 2. 测试分层总览

| 层 | 内容 | 结果 | 命令 |
|---|---|---|---|
| L1 单元测试 | 三重护栏 + Markdown 转换（SqlGuard 4 / UrlGuard 5 / FsSandbox 3 / HtmlToMarkdown 2） | **14/14 通过，BUILD SUCCESS** | `./scripts/build.sh` |
| L2 stdio 冒烟 | 标准握手（initialize → initialized）后逐条调用 9 个工具，18 项断言（协议 2 + 工具目录 7 + 工具行为 9） | **18 通过 / 0 警告 / 0 失败** | `./scripts/smoke-test.sh` |
| L3 SSE 会话 | 客户端仿真：GET /sse → endpoint 协商 → initialize → initialized → tools/list；另做 9 连发 tools/call 并发验证 | **全 200，SSE 流 8/8 响应，工具数 20** | `./scripts/verify-sse.sh` |
| L4 部署终验 | launchctl 常驻、actuator health、`/` 目录首页、审计日志实时性 | **health UP；首页 20 工具；审计 54 条实时记录** | 见 §5 |

## 3. L2 stdio 冒烟明细（18 项）

**协议层（2）**：initialize 返回 serverInfo；serverInfo.name = `aster-agent` ✅

**工具目录（7）**：tools/list 含 web_search / docs_read / db_query / fs_write / github_get_file / browser_screenshot / kb_retrieval（20 工具全量注册）✅

**工具行为（9，含真实外网调用）**：

| 工具 | 断言 | 实测 |
|---|---|---|
| toolbox_catalog | 返回组清单 | ✅ 7 组（web/docs/db/fs/github/browser/kb）含依赖状态说明 |
| fs_list | 列出预置文件 | ✅ welcome.md 出现 |
| fs_read | 读出内容 | ✅ 返回 "aster-agent toolbox smoke file" |
| fs_write | 只读模式被拦 | ✅ `TOOL_ERROR: …已被只读模式禁用…（ASTER_FS_ALLOW_WRITE=true）` |
| db_query | 未配置给指引 | ✅ `TOOL_ERROR: …设置 ASTER_DB_URL/USER/PASSWORD…`（不失败启动） |
| github_search_repos | 真实检索 | ✅ 返回仓库清单（fullName/stars/language/pushedAt） |
| web_fetch | 抓取 example.com | ✅ 返回干净 Markdown（"Example Domain"，279ms，206 字符） |
| browser_navigate | headless Chrome 渲染 | ✅ 返回渲染后可见文本（Chrome 冷启动路径 1.6-1.8s） |
| kb_documents | 有响应 | ✅ 调通主项目接口（未配 token 时按预期返回鉴权指引） |

## 4. L3 SSE 会话明细

- **握手**：`GET /sse` 收到 `event:endpoint`（`/sse/message?sessionId=…`）→ POST initialize 200 → **POST notifications/initialized 200** → POST tools/list 200，SSE 流返回 serverInfo `aster-agent`、20 个工具定义。
- **并发验证**：9 个 tools/call 连发（catalog / fs×3 / db / github / web_fetch / browser_navigate / kb_documents）——**全部执行、全部响应**（SSE 模式无 stdio 的并发竞态，见 §7.1）。
- **主项目兼容性推论**：主项目 MCP 客户端（`HttpClientSseClientTransport`，SDK 0.10.0）按同一协议序列对接；协议版本 2024-11-05 双端一致。唯一前置项是主项目需开 `app.mcp.allow-private-endpoints`（私网地址白名单），已在 ACCEPTANCE §4 写明。

## 5. L4 部署终验

- launchctl：`46868  0  com.zwx.aster-agent-mcp`（常驻、退出码 0）
- health：`GET /actuator/health` → `{"status":"UP"}`
- 首页：`GET /` 渲染工具目录（「共 20 个工具」+ 各组配置状态 + 接入示例）
- 审计：`logs/audit.jsonl` 54 条，本次冒烟实时落盘，样例：
  - `web_fetch  223ms  ok  206 chars`
  - `browser_navigate  1824ms  ok  975 chars`（含 Chrome 冷启动）
  - `kb_documents  22ms  ok  148 chars`

## 6. 测试期间发现并修复的缺陷

| # | 现象 | 根因 | 修复 | 验证 |
|---|---|---|---|---|
| 1 | SSE 模式 initialize 后**第二个请求永久卡死**（服务端收单不回，curl 超时 000） | Spring AI 1.0.0 锁定的 MCP SDK 0.10.0 响应发送路径挂死（SDK 级缺陷） | BOM 升级至 Spring AI 1.1.8（SDK 0.18.3），协议对 0.10.0 客户端向下兼容 | SSE 全链路 + 9 连发全通过（§4） |
| 2 | 漏发 `notifications/initialized` 时所有请求静默无响应，无任何报错 | MCP 会话状态机要求严格握手序列，SDK 对未完成初始化的会话不回错误 | 冒烟/验证脚本按正确序列实现并在 ACCEPTANCE §6 记录该误诊点 | verify-sse.sh 稳定通过 |
| 3 | stdio 模式下并发灌请求：工具全部执行成功（审计可证）但约 2/3 响应丢失 | SDK stdio 传输并发响应竞态 | 明确为已知边界：stdio 按一问一答使用（真实客户端即如此），冒烟脚本改逐条等待；SSE 为主部署形态无此问题 | 串行 11/11、SSE 9 连发均稳定 |
| 4 | browser/web 组访问公网站点被 SSRF 护栏误杀：`目标主机解析到内网/保留地址 fdfe:…` | 本机 fake-IP DNS 代理把公网域名解析为 ULA（fc00::/7）段，原策略对解析结果全量拦 ULA | 护栏分层：**字面 IP 全量拦（含 ULA）；域名解析路径拦环回/链路本地/RFC1918/CGNAT、放行 ULA**；单测覆盖（UrlGuardTest 5 例） | browser_navigate/web_fetch 公网全通；内网字面 IP 仍被拦 |
| 5 | 编译期 4 处小错 | HikariCP 包名误写（com.hikari→com.zaxxer.hikari）、`WebSocket.Listener.onText` 返回类型（void→CompletionStage）、受检异常未包裹（DNS lambda ×2） | 逐一修正 | mvn package 全绿 |

另有一处测试脚本自身缺陷（check 函数嵌套引号导致 `unbound variable`），已随脚本重写消除，不影响服务本体。

## 7. 已知边界与未覆盖项

1. **stdio 并发竞态**（§6.3）：按一问一答使用完全稳定，SSE 无此问题；如后续需要 stdio 并发，需跟踪 SDK 上游修复。
2. **web_search 免钥兜底**：DuckDuckGo Lite 在国内网络常不可达——工具返回带配置指引的明确错误（按设计），配 `SEARCH_API_API_KEY` 后切 searchapi(baidu)。**本次未配 key，未对 searchapi/tavily 真实链路验收**。
3. **db 组**：未配置 `ASTER_DB_URL`，只验收了「未配置→指引」分支与 SqlGuard 单测；真实库查询、自动 LIMIT 包层、超时未在真库上跑。
4. **kb 组鉴权链路**：无 token 只验收 401/403 指引分支；带 token 的真实 RAG 检索（kb_retrieval 命中切片）待用户配 `ASTER_AGENT_TOKEN` 后验收。
5. **browser_screenshot / browser_eval / web_extract_links / docs 组降级链**：实现自洽（与已验证路径共用代码），未逐个出独立冒烟断言；docs 依赖 context7.com 可达性。
6. **Docker 部署**：镜像/compose 已提供，未在本机实测（容器内无浏览器，browser 组按预期降级）。
7. **SSE 端点鉴权**：当前无 token 校验（内网部署前提下的记录决策，见设计文档 §5）。

## 8. 复现命令

```sh
cd ~/Desktop/agent/aster-mcp
./scripts/build.sh        # L1：单测 14/14
./scripts/smoke-test.sh   # L2：stdio 冒烟 18/18（含真实外网调用）
./scripts/verify-sse.sh   # L3：SSE 握手 + 工具列表
curl http://127.0.0.1:8124/actuator/health   # L4：部署终验
open http://127.0.0.1:8124/                  # 目录首页
tail -5 aster-agent/logs/audit.jsonl         # 审计
```
