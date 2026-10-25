package com.zwx.asteragent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * 工具调用审计：每个 tools/call 记一行 JSONL（时间/工具/入参摘要/耗时/状态/返回字节数）。
 * 文件追加写，失败只影响审计不影响工具本身。
 */
public class ToolAudit {

    private static final Logger log = LoggerFactory.getLogger(ToolAudit.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path logFile;
    private final boolean enabled;
    private final Object lock = new Object();

    public ToolAudit(boolean enabled, String logFile) {
        this.enabled = enabled;
        this.logFile = Path.of(logFile).toAbsolutePath().normalize();
        if (enabled) {
            try {
                Files.createDirectories(this.logFile.getParent());
            } catch (IOException e) {
                log.warn("审计目录创建失败，审计将不可用：{}", e.getMessage());
            }
        }
    }

    public void record(String tool, String inputSummary, long durationMs, boolean success, int resultChars, String error) {
        if (!enabled) return;
        try {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("ts", Instant.now().toString());
            node.put("tool", tool);
            node.put("input", truncate(inputSummary, 500));
            node.put("durationMs", durationMs);
            node.put("status", success ? "ok" : "error");
            node.put("resultChars", resultChars);
            if (error != null) node.put("error", truncate(error, 500));
            String line = MAPPER.writeValueAsString(node);
            synchronized (lock) {
                Files.writeString(logFile, line + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Exception e) {
            log.warn("审计写入失败：{}", e.getMessage());
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
