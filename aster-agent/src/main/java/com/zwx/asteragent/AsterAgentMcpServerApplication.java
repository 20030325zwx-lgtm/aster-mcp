package com.zwx.asteragent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * aster-agent 独立 MCP 工具箱服务。
 *
 * <p>定位：把「联网搜索与抓取 / 最新库文档 / 数据库查询 / 文件沙箱 / GitHub / 浏览器自动化 /
 * aster 知识库检索」收敛为一个可单独部署的 MCP Server，供 yu-ai-agent、Claude、Cursor
 * 等任意 MCP 客户端接入。
 *
 * <p>默认 SSE 模式（:8124）；stdio 模式：
 * {@code java -Dspring.ai.mcp.server.stdio=true -Dspring.main.web-application-type=none -jar ...}
 */
@SpringBootApplication
@EnableConfigurationProperties(ToolboxProperties.class)
public class AsterAgentMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AsterAgentMcpServerApplication.class, args);
    }
}
