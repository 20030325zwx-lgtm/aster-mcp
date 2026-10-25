package com.zwx.asteragent.tools.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zwx.asteragent.ToolboxProperties;
import com.zwx.asteragent.core.HttpSupport;
import com.zwx.asteragent.core.OutputLimiter;
import com.zwx.asteragent.core.ToolError;
import com.zwx.asteragent.tools.web.WebFetchTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * docs 组（Context7 位）：解析库的最新官方文档与代码示例。
 * 主路：Context7 公共 API；降级：内置注册表 → web_fetch 官方文档站。
 */
public class DocsTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, String> DOCS_REGISTRY = Map.ofEntries(
            Map.entry("spring-boot", "https://docs.spring.io/spring-boot/index.html"),
            Map.entry("spring-ai", "https://docs.spring.io/spring-ai/reference/index.html"),
            Map.entry("spring", "https://docs.spring.io/spring-framework/reference/index.html"),
            Map.entry("java", "https://docs.oracle.com/en/java/javase/21/docs/api/index.html"),
            Map.entry("python", "https://docs.python.org/3/"),
            Map.entry("javascript", "https://developer.mozilla.org/zh-CN/docs/Web/JavaScript"),
            Map.entry("typescript", "https://www.typescriptlang.org/docs/"),
            Map.entry("react", "https://react.dev/reference/react"),
            Map.entry("vue", "https://vuejs.org/guide/introduction.html"),
            Map.entry("nodejs", "https://nodejs.org/docs/latest/api/"),
            Map.entry("go", "https://go.dev/doc/"),
            Map.entry("rust", "https://doc.rust-lang.org/book/"),
            Map.entry("kubernetes", "https://kubernetes.io/zh-cn/docs/home/"),
            Map.entry("docker", "https://docs.docker.com/reference/"),
            Map.entry("postgresql", "https://www.postgresql.org/docs/current/"),
            Map.entry("redis", "https://redis.io/docs/latest/"));

    private final ToolboxProperties.Docs props;
    private final WebFetchTool fetchTool;
    private final int maxChars;

    public DocsTool(ToolboxProperties.Docs props, WebFetchTool fetchTool, int maxChars) {
        this.props = props;
        this.fetchTool = fetchTool;
        this.maxChars = maxChars;
    }

    @Tool(name = "docs_search", description = "按库名检索可用的最新文档源（Context7 索引），返回文档 ID/标题/简介，"
            + "把返回的 ID 用于 docs_read 可精准拉取该库的最新文档片段。")
    public String search(@ToolParam(description = "库名或关键词，如 spring-ai、react") String library) {
        if (library == null || library.isBlank()) return ToolError.of("library 不能为空");
        try {
            String url = props.getContext7BaseUrl() + "/api/v1/search?query="
                    + URLEncoder.encode(library.trim(), StandardCharsets.UTF_8);
            String body = HttpSupport.get(url, props.getTimeoutMs());
            JsonNode root = MAPPER.readTree(body);
            ArrayNode out = MAPPER.createArrayNode();
            if (root.isArray()) {
                for (int i = 0; i < Math.min(5, root.size()); i++) {
                    JsonNode item = root.get(i);
                    ObjectNode row = out.addObject();
                    row.put("id", item.path("id").asText(""));
                    row.put("title", item.path("title").asText(""));
                    row.put("description", item.path("description").asText(""));
                }
            }
            if (out.isEmpty()) {
                return fallbackSearch(library);
            }
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out);
        } catch (Exception e) {
            return fallbackSearch(library);
        }
    }

    @Tool(name = "docs_read", description = "读取某个库的最新官方文档片段（默认 5000 tokens），优先代码示例与 API 用法，"
            + "可按 topic 过滤。相比通用搜索，本工具返回的是与当前版本一致的权威文档，能显著减少 API 幻觉。")
    public String read(@ToolParam(description = "库 ID 或库名（docs_search 返回的 id，或直接写 spring-ai 这类通用名）") String library,
                       @ToolParam(description = "可选：主题过滤，如 retry、streaming", required = false) String topic,
                       @ToolParam(description = "可选：最大 tokens，默认 5000", required = false) Integer tokens) {
        if (library == null || library.isBlank()) return ToolError.of("library 不能为空");
        int budget = tokens == null ? props.getMaxTokens() : Math.max(500, Math.min(20000, tokens));
        try {
            String id = library.trim();
            String url = props.getContext7BaseUrl() + "/api/v1/" + id + "?tokens=" + budget + "&type=txt";
            if (topic != null && !topic.isBlank()) {
                url += "&topic=" + URLEncoder.encode(topic.trim(), StandardCharsets.UTF_8);
            }
            String body = HttpSupport.get(url, props.getTimeoutMs());
            String content;
            if (body.trim().startsWith("{")) {
                JsonNode node = MAPPER.readTree(body);
                content = node.path("content").asText("");
                if (content.isEmpty()) content = node.path("error").asText("");
            } else {
                content = body;
            }
            if (content.isBlank()) return fallbackRead(library, topic);
            return OutputLimiter.limit("[来源：Context7 " + id + "]\n" + content, maxChars);
        } catch (Exception e) {
            return fallbackRead(library, topic);
        }
    }

    /** Context7 不可达时的降级检索：注册表直答或提示用 web_search。 */
    private String fallbackSearch(String library) {
        String key = library.trim().toLowerCase();
        if (DOCS_REGISTRY.containsKey(key)) {
            return "[Context7 不可达，降级为内置注册表]\n" + key + " 官方文档：" + DOCS_REGISTRY.get(key);
        }
        return "[Context7 不可达，且 '" + library + "' 不在内置注册表中。建议：1) 用 web_search 搜「" + library
                + " 官方文档」；2) 自建 Context7 或配置代理后重试。]";
    }

    private String fallbackRead(String library, String topic) {
        if (!props.isFallbackToFetch()) return ToolError.of("Context7 不可达且降级抓取已关闭");
        String key = library.trim().toLowerCase().replaceAll(".*/", "");
        String url = DOCS_REGISTRY.get(key);
        if (url == null) {
            // 允许直接传官方文档 URL
            if (library.trim().startsWith("http")) url = library.trim();
            else return fallbackSearch(library);
        }
        String fetched = fetchTool.fetch(url);
        if (fetched.startsWith("TOOL_ERROR:")) {
            return "[Context7 与官方文档抓取均失败]\n" + fetched;
        }
        return OutputLimiter.limit("[降级：直接抓取官方文档 " + url + "]\n" + fetched
                + (topic == null || topic.isBlank() ? "" : "\n\n[提示：topic 过滤仅在 Context7 可用时生效，以上为文档首页全文]"), maxChars);
    }
}
