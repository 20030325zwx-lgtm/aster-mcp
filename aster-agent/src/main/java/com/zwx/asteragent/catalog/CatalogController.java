package com.zwx.asteragent.catalog;

import com.zwx.asteragent.ToolboxProperties;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 工具目录首页（SSE 模式下访问 http://127.0.0.1:8124/ ）：
 * 一眼看清服务身份、接入方式、工具清单与配置状态，验收演示入口。
 */
@RestController
public class CatalogController {

    private final ToolboxProperties props;
    private final ToolCallbackProvider toolbox;
    private final String serverName;
    private final int port;

    public CatalogController(ToolboxProperties props, ToolCallbackProvider asterToolbox,
                             @Value("${spring.ai.mcp.server.name:aster-agent}") String serverName,
                             @Value("${server.port:8124}") int port) {
        this.props = props;
        this.toolbox = asterToolbox;
        this.serverName = serverName;
        this.port = port;
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String index() {
        List<String[]> rows = new ArrayList<>();
        for (ToolCallback callback : toolbox.getToolCallbacks()) {
            var definition = callback.getToolDefinition();
            String description = definition.description() == null ? "" : definition.description();
            rows.add(new String[]{definition.name(), description});
        }
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"zh\"><head><meta charset=\"utf-8\">")
                .append("<title>").append(serverName).append(" · MCP 工具箱</title>")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<style>")
                .append("body{font-family:-apple-system,'PingFang SC',sans-serif;max-width:960px;margin:40px auto;padding:0 20px;color:#1a1a1a;background:#fafafa}")
                .append("h1{margin-bottom:4px} .sub{color:#666;margin-bottom:24px}")
                .append(".card{background:#fff;border:1px solid #e5e5e5;border-radius:12px;padding:20px 24px;margin-bottom:16px}")
                .append("code,pre{background:#f0f0f0;border-radius:6px;padding:2px 6px;font-size:13px}")
                .append("pre{padding:12px;overflow:auto} table{width:100%;border-collapse:collapse;font-size:14px}")
                .append("td,th{padding:8px 10px;border-bottom:1px solid #eee;text-align:left;vertical-align:top}")
                .append("th{background:#f7f7f7} .name{white-space:nowrap;font-weight:600} .desc{color:#444}")
                .append(".ok{color:#0a7d32;font-weight:600} .warn{color:#b96a00;font-weight:600}")
                .append("</style></head><body>");
        html.append("<h1>").append(serverName).append(" <span style=\"font-size:16px;color:#888\">MCP 工具箱服务</span></h1>");
        html.append("<div class=\"sub\">独立部署 · SSE 端点 <code>http://").append(host()).append(":").append(port)
                .append("/sse</code> · 健康检查 <code>/actuator/health</code> · 共 ").append(rows.size()).append(" 个工具</div>");

        html.append("<div class=\"card\"><b>快速接入</b><pre>")
                .append("# yu-ai-agent（MCP 服务配置页新增）\n")
                .append("endpoint:  http://").append(host()).append(":").append(port).append("/sse\n\n")
                .append("# Claude Desktop / Cursor 等标准客户端\n")
                .append("{\n  \"mcpServers\": {\n    \"aster-agent\": {\n")
                .append("      \"url\": \"http://").append(host()).append(":").append(port).append("/sse\"\n    }\n  }\n}")
                .append("</pre></div>");

        html.append("<div class=\"card\"><b>配置状态</b><table>");
        html.append(statusRow("web_search", props.getTools().getWeb().isEnabled(),
                props.getTools().getWeb().getApiKey() == null || props.getTools().getWeb().getApiKey().isBlank()
                        ? "provider " + props.getTools().getWeb().getProvider() + " 未配 key，将降级 DuckDuckGo"
                        : "provider " + props.getTools().getWeb().getProvider() + " 已配置 key"));
        html.append(statusRow("db", props.getTools().getDb().isEnabled(),
                props.getTools().getDb().getUrl() == null || props.getTools().getDb().getUrl().isBlank()
                        ? "未配置 ASTER_DB_URL（工具返回指引）" : "数据源已配置（只读护栏）"));
        html.append(statusRow("fs", props.getTools().getFs().isEnabled(),
                "沙箱 " + Path.of(props.getTools().getFs().getRoot()).toAbsolutePath()
                        + (props.getTools().getFs().isAllowWrite() ? "（可写）" : "（只读）")));
        html.append(statusRow("github", props.getTools().getGithub().isEnabled(),
                props.getTools().getGithub().getToken() == null || props.getTools().getGithub().getToken().isBlank()
                        ? "匿名（60 次/小时）" : "token 已配置"));
        html.append(statusRow("browser", props.getTools().getBrowser().isEnabled(),
                chromeFound() ? "Chrome 可用" : "未找到 Chrome（设 CHROME_PATH）"));
        html.append(statusRow("kb", props.getTools().getKb().isEnabled(),
                "目标 " + props.getTools().getKb().getBaseUrl() + "，agentKey=" + props.getTools().getKb().getAgentKey()));
        html.append("</table></div>");

        html.append("<div class=\"card\"><b>工具目录（").append(rows.size()).append("）</b><table>")
                .append("<tr><th>工具</th><th>说明</th></tr>");
        for (String[] row : rows) {
            html.append("<tr><td class=\"name\">").append(escape(row[0])).append("</td><td class=\"desc\">")
                    .append(escape(row[1])).append("</td></tr>");
        }
        html.append("</table></div>");
        html.append("<div class=\"sub\">审计日志：").append(Path.of(props.getAudit().getLogFile()).toAbsolutePath())
                .append(" · 审计开关 ").append(props.getAudit().isEnabled() ? "开" : "关").append("</div>");
        html.append("</body></html>");
        return html.toString();
    }

    private String host() {
        try {
            return InetAddress.getLoopbackAddress().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    private static String statusRow(String group, boolean enabled, String note) {
        return "<tr><td class=\"name\">" + group + "</td><td>"
                + (enabled ? "<span class=\"ok\">启用</span>" : "<span class=\"warn\">停用</span>")
                + " · " + escape(note) + "</td></tr>";
    }

    private static boolean chromeFound() {
        return List.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                        "/Applications/Chromium.app/Contents/MacOS/Chromium",
                        "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge")
                .stream().anyMatch(path -> Files.isExecutable(Path.of(path)));
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
