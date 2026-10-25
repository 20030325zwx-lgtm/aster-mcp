package com.zwx.asteragent.tools.web;

import com.zwx.asteragent.ToolboxProperties;
import com.zwx.asteragent.core.OutputLimiter;
import com.zwx.asteragent.core.ToolError;
import com.zwx.asteragent.core.UrlGuard;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * web_extract_links：页面链接发现（Firecrawl crawl-lite 位），支持正则过滤。
 */
public class WebExtractLinksTool {

    private static final int MAX_LINKS = 100;

    private final UrlGuard urlGuard;
    private final int timeoutMs;

    public WebExtractLinksTool(ToolboxProperties.Web props, UrlGuard urlGuard) {
        this.urlGuard = urlGuard;
        this.timeoutMs = props.getTimeoutMs();
    }

    @Tool(name = "web_extract_links", description = "提取一个网页上的全部链接（绝对 URL，去重，最多 100 条），"
            + "可用 pattern 正则过滤，例如 '^https://docs\\.example\\.com/.+'。适合站点爬取前的链接发现。")
    public String extractLinks(@ToolParam(description = "目标网页 URL") String url,
                               @ToolParam(description = "可选：Java 正则，只保留匹配的链接", required = false) String pattern) {
        try {
            String validated = urlGuard.validate(url);
            Pattern filter = pattern == null || pattern.isBlank() ? null : Pattern.compile(pattern);
            Document document = Jsoup.connect(validated)
                    .timeout(timeoutMs)
                    .userAgent(com.zwx.asteragent.core.HttpSupport.USER_AGENT)
                    .get();
            Set<String> links = new LinkedHashSet<>();
            for (Element anchor : document.select("a[href]")) {
                String href = anchor.absUrl("href");
                if (href.isEmpty() || href.startsWith("javascript:")) continue;
                if (filter != null && !filter.matcher(href).find()) continue;
                links.add(href);
                if (links.size() >= MAX_LINKS) break;
            }
            if (links.isEmpty()) return ToolError.of("未发现匹配的链接" + (filter != null ? "（正则 " + pattern + "）" : ""));
            return OutputLimiter.limit("共 " + links.size() + " 条链接：\n" + String.join("\n", links), 20000);
        } catch (IllegalArgumentException e) {
            return ToolError.of(e.getMessage());
        } catch (Exception e) {
            return ToolError.of(e);
        }
    }
}
