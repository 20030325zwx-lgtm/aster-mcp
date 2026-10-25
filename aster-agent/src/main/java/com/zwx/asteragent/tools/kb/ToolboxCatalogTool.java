package com.zwx.asteragent.tools.kb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zwx.asteragent.ToolboxProperties;
import org.springframework.ai.tool.annotation.Tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * toolbox_catalog 元工具：自描述目录。客户端接入后先调它可以知道有哪些组、哪些工具、
 * 哪些依赖密钥/环境才可用，避免盲试。
 */
public class ToolboxCatalogTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> CHROME_CANDIDATES = List.of(
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "/Applications/Chromium.app/Contents/MacOS/Chromium",
            "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge");

    private final ToolboxProperties props;

    public ToolboxCatalogTool(ToolboxProperties props) {
        this.props = props;
    }

    @Tool(name = "toolbox_catalog", description = "返回本工具箱的目录：工具组、每组工具清单、启用状态与依赖说明"
            + "（哪些需要 API key、哪些需要环境配置）。接入后建议先调用本工具。")
    public String catalog() {
        try {
            ObjectNode out = MAPPER.createObjectNode();
            out.put("server", "aster-agent");
            out.put("version", "0.0.1");
            ArrayNode groups = out.putArray("groups");

            group(groups, "web", props.getTools().getWeb().isEnabled(),
                    List.of("web_search", "web_fetch", "web_extract_links"),
                    "联网搜索与网页抓取",
                    props.getTools().getWeb().getApiKey() == null || props.getTools().getWeb().getApiKey().isBlank()
                            ? "搜索 provider（" + props.getTools().getWeb().getProvider() + "）未配置 key，web_search 将自动降级 DuckDuckGo（可能不可达）；web_fetch/links 无需 key"
                            : "搜索 provider " + props.getTools().getWeb().getProvider() + " 已配置，开箱可用");
            group(groups, "docs", props.getTools().getDocs().isEnabled(),
                    List.of("docs_search", "docs_read"),
                    "最新库文档（Context7 风格）",
                    "主路 Context7 公共 API，不可达时降级为官方文档站抓取");
            group(groups, "db", props.getTools().getDb().isEnabled(),
                    List.of("db_schema", "db_query"),
                    "PostgreSQL 只读查询",
                    props.getTools().getDb().getUrl() == null || props.getTools().getDb().getUrl().isBlank()
                            ? "未配置 ASTER_DB_URL，工具会返回配置指引"
                            : "已连接（只读护栏：单语句/禁写/自动 LIMIT " + props.getTools().getDb().getMaxRows() + " 行）");
            group(groups, "fs", props.getTools().getFs().isEnabled(),
                    List.of("fs_list", "fs_read", "fs_write"),
                    "沙箱文件读写",
                    "沙箱根 " + Path.of(props.getTools().getFs().getRoot()).toAbsolutePath()
                            + "；fs_write 当前=" + (props.getTools().getFs().isAllowWrite() ? "可写" : "只读（默认）"));
            group(groups, "github", props.getTools().getGithub().isEnabled(),
                    List.of("github_search_repos", "github_get_file", "github_list_issues", "github_list_pulls"),
                    "GitHub 仓库/文件/Issue/PR",
                    props.getTools().getGithub().getToken() == null || props.getTools().getGithub().getToken().isBlank()
                            ? "匿名模式（60 次/小时限流），配置 GITHUB_TOKEN 提升额度"
                            : "已配置 token（5000 次/小时）");
            group(groups, "browser", props.getTools().getBrowser().isEnabled(),
                    List.of("browser_navigate", "browser_eval", "browser_screenshot"),
                    "无头 Chrome 浏览器自动化",
                    chromeFound() ? "已探测到本机 Chrome，开箱可用" : "未探测到 Chrome，请安装或设置 CHROME_PATH");
            group(groups, "kb", props.getTools().getKb().isEnabled(),
                    List.of("kb_documents", "kb_retrieval"),
                    "aster 知识库（yu-ai-agent 业务）",
                    "目标 " + props.getTools().getKb().getBaseUrl() + "，agentKey=" + props.getTools().getKb().getAgentKey()
                            + (props.getTools().getKb().getToken() == null || props.getTools().getKb().getToken().isBlank()
                            ? "，未配置 token（只读接口需要登录态时请设 ASTER_AGENT_TOKEN）" : "，已配置 token"));

            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out);
        } catch (Exception e) {
            return "TOOL_ERROR: " + e.getMessage();
        }
    }

    private static boolean chromeFound() {
        return CHROME_CANDIDATES.stream().anyMatch(path -> Files.isExecutable(Path.of(path)));
    }

    private static void group(ArrayNode groups, String name, boolean enabled, List<String> tools,
                              String summary, String note) {
        ObjectNode group = groups.addObject();
        group.put("group", name);
        group.put("enabled", enabled);
        group.put("summary", summary);
        group.put("note", note);
        group.set("tools", MAPPER.valueToTree(tools));
    }
}
