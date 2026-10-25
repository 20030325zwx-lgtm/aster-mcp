package com.zwx.asteragent.tools.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zwx.asteragent.ToolboxProperties;
import com.zwx.asteragent.core.HttpSupport;
import com.zwx.asteragent.core.OutputLimiter;
import com.zwx.asteragent.core.ToolError;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * github 组：仓库检索 / 文件读取 / Issue 与 PR 列表（只读）。
 * 无 token 可用（匿名 60 次/小时），GITHUB_TOKEN 提至 5000 次/小时。
 */
public class GithubTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API = "https://api.github.com";
    private static final int MAX_FILE_BYTES = 512 * 1024;

    private final ToolboxProperties.Github props;
    private final int maxChars;

    public GithubTool(ToolboxProperties.Github props, int maxChars) {
        this.props = props;
        this.maxChars = maxChars;
    }

    @Tool(name = "github_search_repos", description = "在 GitHub 检索仓库，返回 full_name/简介/语言/星数/最近推送，"
            + "用于找参考实现或确认库的活跃度。")
    public String searchRepos(@ToolParam(description = "检索关键词，如 mcp server java") String query,
                              @ToolParam(description = "可选：返回条数 1-30", required = false) Integer limit) {
        if (query == null || query.isBlank()) return ToolError.of("query 不能为空");
        int count = Math.max(1, Math.min(30, limit == null ? props.getPerPage() : limit));
        try {
            String url = API + "/search/repositories?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&sort=stars&order=desc&per_page=" + count;
            JsonNode items = MAPPER.readTree(get(url)).get("items");
            ArrayNode out = MAPPER.createArrayNode();
            if (items != null) for (JsonNode repo : items) {
                ObjectNode row = out.addObject();
                row.put("fullName", repo.path("full_name").asText());
                row.put("url", repo.path("html_url").asText());
                row.put("description", repo.path("description").asText(""));
                row.put("language", repo.path("language").asText(""));
                row.put("stars", repo.path("stargazers_count").asInt(0));
                row.put("pushedAt", repo.path("pushed_at").asText(""));
            }
            if (out.isEmpty()) return "无匹配仓库：" + query;
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out);
        } catch (Exception e) {
            return ToolError.of(rateLimitHint(e));
        }
    }

    @Tool(name = "github_get_file", description = "读取 GitHub 仓库内一个文件的内容（默认分支或指定 ref），"
            + "如 README.md、pom.xml、源码，超过 512KB 拒绝。")
    public String getFile(@ToolParam(description = "仓库所有者") String owner,
                          @ToolParam(description = "仓库名") String repo,
                          @ToolParam(description = "仓库内文件路径，如 README.md") String path,
                          @ToolParam(description = "可选：分支/标签/commit", required = false) String ref) {
        try {
            String url = API + "/repos/" + owner + "/" + repo + "/contents/" + path
                    + (ref == null || ref.isBlank() ? "" : "?ref=" + URLEncoder.encode(ref, StandardCharsets.UTF_8));
            JsonNode node = MAPPER.readTree(get(url));
            if (!"base64".equals(node.path("encoding").asText())) {
                return ToolError.of("目标不是 base64 文件（可能是目录或超大文件）：" + node.path("type").asText());
            }
            if (node.path("size").asLong(0) > MAX_FILE_BYTES) return ToolError.of("文件超过 512KB 上限");
            byte[] bytes = Base64.getDecoder().decode(node.path("content").asText().replaceAll("\\s", ""));
            return OutputLimiter.limit(new String(bytes, StandardCharsets.UTF_8), maxChars);
        } catch (Exception e) {
            return ToolError.of(rateLimitHint(e));
        }
    }

    @Tool(name = "github_list_issues", description = "列出仓库最近 Issue（自动排除 Pull Request），返回编号/标题/状态/标签。")
    public String listIssues(@ToolParam(description = "仓库所有者") String owner,
                             @ToolParam(description = "仓库名") String repo,
                             @ToolParam(description = "可选：open / closed / all，默认 open", required = false) String state,
                             @ToolParam(description = "可选：返回条数 1-30", required = false) Integer limit) {
        int count = Math.max(1, Math.min(30, limit == null ? props.getPerPage() : limit));
        String effectiveState = state == null || state.isBlank() ? "open" : state;
        try {
            String url = API + "/repos/" + owner + "/" + repo + "/issues?state=" + effectiveState
                    + "&per_page=" + Math.min(30, count * 2) + "&sort=created&direction=desc";
            JsonNode items = MAPPER.readTree(get(url));
            ArrayNode out = MAPPER.createArrayNode();
            if (items != null && items.isArray()) {
                for (JsonNode issue : items) {
                    if (!issue.path("pull_request").isMissingNode()) continue;  // issues API 会混入 PR
                    if (out.size() >= count) break;
                    ObjectNode row = out.addObject();
                    row.put("number", issue.path("number").asInt());
                    row.put("title", issue.path("title").asText());
                    row.put("state", issue.path("state").asText());
                    row.put("url", issue.path("html_url").asText());
                    row.put("labels", issue.path("labels").findValuesAsText("name").toString());
                    row.put("createdAt", issue.path("created_at").asText());
                }
            }
            if (out.isEmpty()) return "无匹配 Issue（state=" + effectiveState + "）";
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out);
        } catch (Exception e) {
            return ToolError.of(rateLimitHint(e));
        }
    }

    @Tool(name = "github_list_pulls", description = "列出仓库最近 Pull Request，返回编号/标题/状态/分支/作者。")
    public String listPulls(@ToolParam(description = "仓库所有者") String owner,
                            @ToolParam(description = "仓库名") String repo,
                            @ToolParam(description = "可选：open / closed / all，默认 open", required = false) String state,
                            @ToolParam(description = "可选：返回条数 1-30", required = false) Integer limit) {
        int count = Math.max(1, Math.min(30, limit == null ? props.getPerPage() : limit));
        String effectiveState = state == null || state.isBlank() ? "open" : state;
        try {
            String url = API + "/repos/" + owner + "/" + repo + "/pulls?state=" + effectiveState
                    + "&per_page=" + count + "&sort=created&direction=desc";
            JsonNode items = MAPPER.readTree(get(url));
            ArrayNode out = MAPPER.createArrayNode();
            if (items != null && items.isArray()) for (JsonNode pull : items) {
                ObjectNode row = out.addObject();
                row.put("number", pull.path("number").asInt());
                row.put("title", pull.path("title").asText());
                row.put("state", pull.path("state").asText());
                row.put("draft", pull.path("draft").asBoolean(false));
                row.put("author", pull.path("user").path("login").asText(""));
                row.put("branch", pull.path("head").path("ref").asText(""));
                row.put("url", pull.path("html_url").asText());
            }
            if (out.isEmpty()) return "无匹配 PR（state=" + effectiveState + "）";
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out);
        } catch (Exception e) {
            return ToolError.of(rateLimitHint(e));
        }
    }

    private String get(String url) {
        return props.getToken() == null || props.getToken().isBlank()
                ? HttpSupport.get(url, props.getTimeoutMs(), "Accept", "application/vnd.github+json")
                : HttpSupport.get(url, props.getTimeoutMs(), "Accept", "application/vnd.github+json",
                        "Authorization", "Bearer " + props.getToken());
    }

    private String rateLimitHint(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (message.contains("403") || message.contains("429")) {
            return message + "（匿名限流：配置环境变量 GITHUB_TOKEN 可提升至 5000 次/小时）";
        }
        return message;
    }
}
