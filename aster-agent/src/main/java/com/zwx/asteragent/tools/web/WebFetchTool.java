package com.zwx.asteragent.tools.web;

import com.zwx.asteragent.ToolboxProperties;
import com.zwx.asteragent.core.HtmlToMarkdown;
import com.zwx.asteragent.core.OutputLimiter;
import com.zwx.asteragent.core.ToolError;
import com.zwx.asteragent.core.UrlGuard;
import org.jsoup.Jsoup;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * web_fetch：网页抓取并转为干净 Markdown（Firecrawl 位）。过 SSRF 护栏，限制响应体大小。
 */
public class WebFetchTool {

    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    private final UrlGuard urlGuard;
    private final int timeoutMs;
    private final int maxChars;

    public WebFetchTool(ToolboxProperties.Web props, UrlGuard urlGuard, int maxChars) {
        this.urlGuard = urlGuard;
        this.timeoutMs = props.getTimeoutMs();
        this.maxChars = maxChars;
    }

    @Tool(name = "web_fetch", description = "抓取指定 http(s) 网页并转换为干净的 Markdown（剔除导航/脚本/样式），"
            + "适合精读一篇文章或文档页。返回正文超长会自动截断。")
    public String fetch(@ToolParam(description = "目标网页 URL（http/https）") String url) {
        try {
            String validated = urlGuard.validate(url);
            var document = Jsoup.connect(validated)
                    .timeout(timeoutMs)
                    .userAgent(com.zwx.asteragent.core.HttpSupport.USER_AGENT)
                    .maxBodySize(MAX_BODY_BYTES)
                    .get();
            document.outputSettings(new org.jsoup.nodes.Document.OutputSettings().prettyPrint(false));
            return OutputLimiter.limit(HtmlToMarkdown.convert(document.html(), validated), maxChars);
        } catch (IllegalArgumentException e) {
            return ToolError.of(e.getMessage());
        } catch (Exception e) {
            return ToolError.of(e);
        }
    }
}
