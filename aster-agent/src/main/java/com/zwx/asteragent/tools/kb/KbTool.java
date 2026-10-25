package com.zwx.asteragent.tools.kb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwx.asteragent.ToolboxProperties;
import com.zwx.asteragent.core.HttpSupport;
import com.zwx.asteragent.core.OutputLimiter;
import com.zwx.asteragent.core.ToolError;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * kb 组（业务契合）：直接调用 yu-ai-agent（aster-agent 主服务）的知识库 API。
 *
 * <ul>
 *   <li>kb_documents：列出指定 agentKey 的知识文档（生命周期状态）；</li>
 *   <li>kb_retrieval：对某文档发起真实 RAG 检索验证（与主项目 verify-retrieval 同源），
 *       供评测集维护、检索质量排查使用。</li>
 * </ul>
 * base-url 是管理员显式配置的内网地址，因此不走 UrlGuard；鉴权 token 可选（ASTER_AGENT_TOKEN）。
 */
public class KbTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolboxProperties.Kb props;
    private final int maxChars;

    public KbTool(ToolboxProperties.Kb props, int maxChars) {
        this.props = props;
        this.maxChars = maxChars;
    }

    @Tool(name = "kb_documents", description = "列出 yu-ai-agent 某个智能体（agentKey）下的知识库文档清单及生命周期状态"
            + "（INDEXING/READY/ACTIVE/REJECTED 等），用于知识运营与排查。")
    public String documents(@ToolParam(description = "可选：智能体 key，默认服务配置的 agentKey", required = false) String agentKey) {
        String key = agentKey == null || agentKey.isBlank() ? props.getAgentKey() : agentKey.trim();
        try {
            String url = trimBase() + "/ai/agent-knowledge/documents?agentKey="
                    + URLEncoder.encode(key, StandardCharsets.UTF_8);
            String body = authedGet(url);
            JsonNode root = MAPPER.readTree(body);
            if (!root.isArray()) return OutputLimiter.limit(pretty(root), maxChars);
            int total = root.size();
            StringBuilder out = new StringBuilder("知识库（agentKey=" + key + "）共 " + total + " 份文档：\n");
            for (JsonNode document : root) {
                out.append("- ").append(document.path("filename").asText(document.path("name").asText("?")))
                        .append("  [").append(document.path("lifecycleStatus").asText("?")).append("]")
                        .append("  id=").append(document.path("id").asText(document.path("documentId").asText("?")))
                        .append('\n');
            }
            return OutputLimiter.limit(out.toString(), maxChars);
        } catch (IllegalArgumentException e) {
            return ToolError.of(hint(e.getMessage()));
        } catch (Exception e) {
            return ToolError.of(e);
        }
    }

    @Tool(name = "kb_retrieval", description = "对知识库某份文档发起一次真实 RAG 向量检索（与主服务 verify-retrieval 同一链路），"
            + "返回命中的切片内容与相似度分数。用于：评测集维护、检索质量排查、引用溯源。")
    public String retrieval(@ToolParam(description = "文档 ID（可先 kb_documents 获取）") String documentId,
                            @ToolParam(description = "检索 query，如「员工年假有几天」") String query,
                            @ToolParam(description = "可选：智能体 key，默认服务配置的 agentKey", required = false) String agentKey) {
        if (documentId == null || documentId.isBlank()) return ToolError.of("documentId 不能为空");
        if (query == null || query.isBlank()) return ToolError.of("query 不能为空");
        String key = agentKey == null || agentKey.isBlank() ? props.getAgentKey() : agentKey.trim();
        try {
            String url = trimBase() + "/ai/agent-knowledge/documents/" + URLEncoder.encode(documentId, StandardCharsets.UTF_8)
                    + "/verify-retrieval?agentKey=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
            String body;
            if (props.getToken() == null || props.getToken().isBlank()) {
                body = HttpSupport.post(url, MAPPER.writeValueAsString(java.util.Map.of("query", query.trim())),
                        props.getTimeoutMs());
            } else {
                body = HttpSupport.post(url, MAPPER.writeValueAsString(java.util.Map.of("query", query.trim())),
                        props.getTimeoutMs(), "Authorization", "Bearer " + props.getToken());
            }
            return OutputLimiter.limit(pretty(MAPPER.readTree(body)), maxChars);
        } catch (IllegalArgumentException e) {
            return ToolError.of(hint(e.getMessage()));
        } catch (Exception e) {
            return ToolError.of(e);
        }
    }

    private String authedGet(String url) {
        return props.getToken() == null || props.getToken().isBlank()
                ? HttpSupport.get(url, props.getTimeoutMs())
                : HttpSupport.get(url, props.getTimeoutMs(), "Authorization", "Bearer " + props.getToken());
    }

    private String trimBase() {
        String base = props.getBaseUrl() == null ? "" : props.getBaseUrl().trim();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private String pretty(JsonNode node) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }

    /** 鉴权/权限类错误给出可操作的指引。 */
    private String hint(String message) {
        if (message != null && (message.contains("401") || message.contains("403"))) {
            return message + "。主服务接口需要登录态且有知识库权限：1) 用 /api/auth/login 获取 token，"
                    + "配置到环境变量 ASTER_AGENT_TOKEN；2) 账号需为该 agentKey 的 REVIEWER/ADMIN 或 WORKFLOW 角色。";
        }
        if (message != null && message.contains("请求失败")) {
            return message + "。请确认 yu-ai-agent 主服务已启动（默认 http://127.0.0.1:8123/api/health 返回 ok），"
                    + "或通过 ASTER_AGENT_BASE_URL 修改地址。";
        }
        return message == null ? "未知错误" : message;
    }
}
