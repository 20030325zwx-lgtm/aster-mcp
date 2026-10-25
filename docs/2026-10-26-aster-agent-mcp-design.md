# aster-agent MCP 工具箱 · 设计文档（2026-10-26）

## 1. 背景与目标

主项目 `yu-ai-agent`（artifactId `aster-agent`）已具备 MCP **客户端**能力（DB 配置外部 server、工具 allowlist、ToolGuard），仓库内也有图片/文档两个单点 MCP 子服务。用户需要一个**更全面、更专业、可独立部署**的 MCP 服务，覆盖 2026 年市面热度最高的五类能力，并与 aster 业务契合。

本服务（工作区 `aster-mcp/`，服务名 `aster-agent`，artifactId `aster-agent-mcp-server`）由此而来：不是主项目的子模块，而是同级目录的独立工程，单独构建、单独部署、被任意 MCP 客户端消费。

## 2. 能力映射（需求 → 实现）

| 需求（市面热门） | 实现 | 说明 |
|---|---|---|
| Exa / Firecrawl | web_search / web_fetch / web_extract_links | searchapi（主项目同款、baidu 引擎对国内友好）+ tavily 双 provider，无 key 降级 DuckDuckGo Lite；jsoup 抓取转干净 Markdown |
| Context7 | docs_search / docs_read | 主路 Context7 公共 API（token 预算、topic 过滤），降级内置注册表 + web_fetch 官方文档站 |
| Postgres / Supabase | db_schema / db_query | 独立 Hikari 只读池 + SqlGuard 护栏（见 §3） |
| Filesystem / GitHub | fs_list / fs_read / fs_write；github_search_repos / get_file / list_issues / list_pulls | fs 走沙箱；github 纯 REST 只读、token 可选 |
| Playwright / Chrome DevTools | browser_navigate / browser_eval / browser_screenshot | **不引 Node 生态**：JDK 内置 `java.net.http.WebSocket` 直连 headless Chrome 的 CDP（/json/list 拿 page target → Runtime.evaluate / Page.navigate / Page.captureScreenshot），进程按需拉起、单页单飞 |
| 业务契合（aster） | kb_documents / kb_retrieval | 直接调主项目 `/api/ai/agent-knowledge/*`（清单 + verify-retrieval 真实 RAG 检索），服务评测集维护与检索排查 |
| 元能力 | toolbox_catalog | 自描述目录：组、工具、启用状态、依赖缺什么——外部 Agent 接入后先调它，避免盲试 |

## 3. 架构与关键决策

**技术栈**：Spring Boot 3.4.5 + Spring AI `spring-ai-starter-mcp-server-webmvc`。选它而非手写协议：与主项目客户端（`HttpClientSseClientTransport`）同生态、SSE/stdio 双传输开箱即用、工具注解（`@Tool`）模型成熟。

**版本踩坑记录（重要交接）**：
1. Spring AI 1.0.0（MCP SDK 0.10.0）存在响应发送挂死问题——SSE 模式下 initialize 之后第二个请求直接卡死（服务端收单不回、servlet 线程挂起）。升级 **Spring AI 1.1.8（MCP SDK 0.18.3）** 解决。协议层与主项目 0.10.0 客户端向下兼容（2024-11-05 协议版本协商）。
2. MCP 会话状态机要求严格顺序：**initialize → notifications/initialized → 其它请求**。少了 notification，SDK 表现为"收单不回"而非报错，极易误诊为传输故障（本服务 verify-sse.sh / smoke-test.sh 均已按正确顺序实现）。
3. stdio 传输在客户端**并发灌请求**时存在响应竞态（工具全部执行成功、部分响应丢失，审计日志可证）；一问一答模式下 11/11 稳定。真实 MCP 客户端均为请求-响应配对，SSE 模式实测 9 连发无碍，故按已知边界记录而非阻塞项。
4. 本机为 fake-IP DNS 代理环境（公网域名解析为 `fdfe:…` ULA 段），SSRF 护栏若对域名解析结果全量拦 ULA 会误杀全部外站。策略收敛为：**字面 IP 全量拦（含 ULA）；域名解析路径拦环回/链路本地/RFC1918/CGNAT、放行 ULA**。

**工具装配**：`ToolboxConfig` 按配置开关逐组实例化工具对象（组内共享 UrlGuard/审计等设施），经 `MethodToolCallbackProvider` 生成 callbacks，再统一包 `AuditingToolCallback` 装饰器（耗时/状态/规模 → JSONL 审计），业务代码零侵入。组开关与全部参数收敛在 `aster.*` 配置（`ToolboxProperties`），环境变量全覆盖。

**护栏体系**（详见 README 安全模型）：SqlGuard（白名单首词 + 全文禁写 + 单语句 + 自动 LIMIT + 超时）、FsSandbox（根 jail + toRealPath 复核 + 大小/条目上限 + 默认只读）、UrlGuard（见上）。所有护栏单测覆盖（14 用例）。

**错误约定**：工具失败返回 `TOOL_ERROR: <原因+指引>` 字符串（对齐主项目 `SEARCH_UNAVAILABLE` 风格），模型可读原因自行调整；未配置的依赖（db url、搜索 key）不阻断启动，调用时给配置指引——**demo 永远不因缺 key 而 503**。

## 4. 部署形态

- **本机常驻**：launchctl `com.zwx.aster-agent-mcp`（对齐主仓库 AGENTS.md 的 com.zwx.* 惯例），日志 `/tmp/aster-agent-mcp.log`，`scripts/launchctl.sh` 全生命周期管理。
- **容器**：Dockerfile（temurin 21 JRE）+ docker-compose（volume 持久化 sandbox/audit；`host.docker.internal` 访问主服务；容器内无浏览器，browser 组按预期给指引）。
- **stdio**：与主项目 `services/*-mcp` 同款启动参数，供 stdio 型客户端嵌起。

## 5. 边界与后续路线

当前不做（记录备查）：MCP 鉴权（SSE 端点暂无 token 校验，内网部署前提下接受，后续可加 Header 校验与主项目 BEARER 配置对齐）；github 写操作（create issue/PR，需求出现再加 + allow-write 双开关）；web_crawl 多页 BFS；MCP resources/prompts 能力面。

后续可做：工具级 rate limit；审计接入 Langfuse；kb 组扩展 workflow-status（n8n 案件三阶段回显）；Streamable HTTP 传输（2025-03-26 协议，SDK 0.18 已支持，SSE 仍为主）。
