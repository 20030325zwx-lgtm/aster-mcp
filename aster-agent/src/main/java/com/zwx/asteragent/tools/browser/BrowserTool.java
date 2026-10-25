package com.zwx.asteragent.tools.browser;

import com.zwx.asteragent.core.ToolError;
import com.zwx.asteragent.core.UrlGuard;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * browser 组（Playwright/Chrome DevTools 位）：headless Chrome 页面操作。
 * 会话复用见 {@link BrowserSession}；所有 URL 先过 SSRF 护栏。
 */
public class BrowserTool {

    private final BrowserSession session;
    private final UrlGuard urlGuard;
    private final int maxChars;

    public BrowserTool(BrowserSession session, UrlGuard urlGuard, int maxChars) {
        this.session = session;
        this.urlGuard = urlGuard;
        this.maxChars = maxChars;
    }

    @Tool(name = "browser_navigate", description = "用无头 Chrome 打开网页并返回渲染后的可见文本（JS 执行后的 DOM），"
            + "适合抓取 web_fetch 拿不到的动态页面（SPA、需要执行脚本才能出内容的站点）。")
    public String navigate(@ToolParam(description = "目标网页 URL") String url) {
        try {
            String validated = urlGuard.validate(url);
            return session.navigate(validated, maxChars);
        } catch (IllegalArgumentException e) {
            return ToolError.of(e.getMessage());
        } catch (Exception e) {
            return ToolError.of(e);
        }
    }

    @Tool(name = "browser_eval", description = "在无头 Chrome 当前页面（或先导航到 url）执行一段 JS 表达式并返回结果，"
            + "支持 await Promise。适合提取页面结构化数据、触发按钮、读取前端状态。")
    public String evaluate(@ToolParam(description = "可选：先导航到的 URL，留空则用当前已打开页面", required = false) String url,
                           @ToolParam(description = "要执行的 JS 表达式") String jsExpression) {
        try {
            String validated = url == null || url.isBlank() ? null : urlGuard.validate(url);
            return session.evaluate(validated, jsExpression, maxChars);
        } catch (IllegalArgumentException e) {
            return ToolError.of(e.getMessage());
        } catch (Exception e) {
            return ToolError.of(e);
        }
    }

    @Tool(name = "browser_screenshot", description = "用无头 Chrome 对网页整页截图，PNG 保存到服务沙箱 screenshots 目录，"
            + "返回文件路径。适合 UI 验收与页面取证。")
    public String screenshot(@ToolParam(description = "目标网页 URL") String url) {
        try {
            String validated = urlGuard.validate(url);
            return session.screenshot(validated);
        } catch (IllegalArgumentException e) {
            return ToolError.of(e.getMessage());
        } catch (Exception e) {
            return ToolError.of(e);
        }
    }
}
