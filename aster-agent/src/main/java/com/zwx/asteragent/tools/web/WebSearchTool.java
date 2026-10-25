package com.zwx.asteragent.tools.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zwx.asteragent.ToolboxProperties;
import com.zwx.asteragent.core.HttpSupport;
import com.zwx.asteragent.core.OutputLimiter;
import com.zwx.asteragent.core.ToolError;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * web_search：联网搜索（Exa 位）。provider=searchapi（主项目同款，engine=baidu 对国内友好）/tavily，
 * 无 key 自动降级 DuckDuckGo Lite；全部失败时返回带配置指引的错误。
 */
public class WebSearchTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SEARCHAPI_URL = "https://www.searchapi.io/api/v1/search";
    private static final String TAVILY_URL = "https://api.tavily.com/search";
    private static final String DDG_LITE_URL = "https://lite.duckduckgo.com/lite/";

    private final ToolboxProperties.Web props;
    private final int maxChars;

    public WebSearchTool(ToolboxProperties.Web props, int maxChars) {
        this.props = props;
        this.maxChars = maxChars;
    }

    @Tool(name = "web_search", description = "联网搜索引擎查询，返回标题/链接/摘要列表。适用于时事、公开资料、"
            + "不确定的外部事实。可指定 max_results（默认 8）。")
    public String search(@ToolParam(description = "搜索关键词") String query,
                         @ToolParam(description = "最大返回条数，1-10", required = false) Integer maxResults) {
        if (query == null || query.isBlank()) return ToolError.of("query 不能为空");
        int limit = Math.max(1, Math.min(10, maxResults == null ? props.getMaxResults() : maxResults));
        try {
            String provider = props.getProvider() == null ? "searchapi" : props.getProvider().trim().toLowerCase();
            String result = switch (provider) {
                case "searchapi" -> searchWithSearchApi(query, limit);
                case "tavily" -> searchWithTavily(query, limit);
                default -> ToolError.of("不支持的 provider '" + provider + "'（支持 searchapi / tavily）");
            };
            if (!ToolError.isError(result)) return OutputLimiter.limit(result, maxChars);
            // 有 key 的 provider 失败 → 尝试免钥兜底
            if (props.isKeylessFallback()) {
                String fallback = searchWithDuckDuckGo(query, limit);
                if (!ToolError.isError(fallback)) {
                    return OutputLimiter.limit("[注：主 provider " + provider + " 不可用，以下为 DuckDuckGo 兜底结果]\n" + fallback, maxChars);
                }
            }
            return result;
        } catch (Exception e) {
            return ToolError.of(e);
        }
    }

    private String searchWithSearchApi(String query, int limit) {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            return props.isKeylessFallback() ? searchWithDuckDuckGo(query, limit)
                    : ToolError.of("searchapi 未配置 API key：请设置环境变量 SEARCH_API_API_KEY（与主项目 yu-ai-agent 同一来源）");
        }
        String url = SEARCHAPI_URL + "?q=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8)
                + "&engine=" + java.net.URLEncoder.encode(props.getEngine(), java.nio.charset.StandardCharsets.UTF_8)
                + "&api_key=" + java.net.URLEncoder.encode(props.getApiKey(), java.nio.charset.StandardCharsets.UTF_8);
        String body = HttpSupport.get(url, props.getTimeoutMs());
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root.hasNonNull("error")) return ToolError.of("searchapi 返回错误：" + root.get("error").asText());
            JsonNode organic = root.get("organic_results");
            if (organic == null || !organic.isArray() || organic.isEmpty()) return ToolError.of("searchapi 无结果（engine=" + props.getEngine() + "）");
            ArrayNode out = MAPPER.createArrayNode();
            for (int i = 0; i < Math.min(limit, organic.size()); i++) {
                JsonNode item = organic.get(i);
                ObjectNode row = out.addObject();
                row.put("title", item.path("title").asText(""));
                row.put("url", item.path("link").asText(""));
                row.put("snippet", item.path("snippet").asText(""));
            }
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out);
        } catch (IllegalArgumentException e) {
            return ToolError.of(e.getMessage());
        } catch (Exception e) {
            return ToolError.of("searchapi 响应解析失败：" + e.getMessage());
        }
    }

    private String searchWithTavily(String query, int limit) {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            return ToolError.of("tavily 未配置 API key：请设置环境变量 SEARCH_API_API_KEY 并将 provider 设为 tavily");
        }
        try {
            String body = HttpSupport.post(TAVILY_URL, MAPPER.writeValueAsString(java.util.Map.of(
                            "api_key", props.getApiKey(), "query", query,
                            "search_depth", "basic", "max_results", limit, "include_answer", false)),
                    props.getTimeoutMs());
            JsonNode results = MAPPER.readTree(body).get("results");
            if (results == null || !results.isArray() || results.isEmpty()) return ToolError.of("tavily 无结果");
            ArrayNode out = MAPPER.createArrayNode();
            for (JsonNode item : results) {
                ObjectNode row = out.addObject();
                row.put("title", item.path("title").asText(""));
                row.put("url", item.path("url").asText(""));
                row.put("snippet", item.path("content").asText(""));
            }
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out);
        } catch (IllegalArgumentException e) {
            return ToolError.of(e.getMessage());
        } catch (Exception e) {
            return ToolError.of("tavily 请求失败：" + e.getMessage());
        }
    }

    /** 免钥兜底：DuckDuckGo Lite HTML 解析（国内网络可能不可达）。 */
    private String searchWithDuckDuckGo(String query, int limit) {
        try {
            Document document = Jsoup.connect(DDG_LITE_URL)
                    .data("q", query)
                    .timeout(props.getTimeoutMs())
                    .userAgent(HttpSupport.USER_AGENT)
                    .post();
            ArrayNode out = MAPPER.createArrayNode();
            for (Element link : document.select("a.result-link")) {
                if (out.size() >= limit) break;
                ObjectNode row = out.addObject();
                row.put("title", link.text());
                row.put("url", link.absUrl("href"));
                row.put("snippet", "");
            }
            // 摘要在 .result-snippet，与链接顺序对应
            var snippets = document.select(".result-snippet");
            for (int i = 0; i < out.size() && i < snippets.size(); i++) {
                ((ObjectNode) out.get(i)).put("snippet", snippets.get(i).text());
            }
            if (out.isEmpty()) return ToolError.of("DuckDuckGo 无结果或被限流（国内网络常见），建议配置 SEARCH_API_API_KEY 使用 searchapi");
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out);
        } catch (Exception e) {
            return ToolError.of("DuckDuckGo 兜底不可达（" + e.getMessage() + "）。建议配置 SEARCH_API_API_KEY（与主项目同款 key）获得稳定搜索");
        }
    }
}
