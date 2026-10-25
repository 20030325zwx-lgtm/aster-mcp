package com.zwx.asteragent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 审计装饰器：包住每个 ToolCallback，记录调用耗时/状态/返回规模，业务零侵入。
 */
public class AuditingToolCallback implements ToolCallback {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolCallback delegate;
    private final ToolAudit audit;

    public AuditingToolCallback(ToolCallback delegate, ToolAudit audit) {
        this.delegate = delegate;
        this.audit = audit;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return audited(toolInput, () -> delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, org.springframework.ai.chat.model.ToolContext toolContext) {
        return audited(toolInput, () -> delegate.call(toolInput, toolContext));
    }

    private interface Invocation {
        String invoke();
    }

    private String audited(String toolInput, Invocation invocation) {
        long start = System.nanoTime();
        String result = null;
        try {
            result = invocation.invoke();
            return result;
        } catch (Exception e) {
            result = ToolError.of(e);
            throw e;
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            boolean success = !ToolError.isError(result);
            audit.record(delegate.getToolDefinition().name(), summarizeInput(toolInput),
                    durationMs, success, result == null ? 0 : result.length(),
                    success ? null : result);
        }
    }

    private static String summarizeInput(String toolInput) {
        try {
            JsonNode node = MAPPER.readTree(toolInput == null || toolInput.isBlank() ? "{}" : toolInput);
            StringBuilder summary = new StringBuilder();
            node.fields().forEachRemaining(entry -> {
                if (!summary.isEmpty()) summary.append(", ");
                String value = entry.getValue().asText("");
                summary.append(entry.getKey()).append('=')
                        .append(value.length() > 120 ? value.substring(0, 120) + "…" : value);
            });
            return summary.toString();
        } catch (Exception e) {
            return toolInput == null ? "" : toolInput.substring(0, Math.min(200, toolInput.length()));
        }
    }
}
