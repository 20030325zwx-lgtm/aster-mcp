package com.zwx.asteragent.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlToMarkdownTest {

    @Test
    void convertsStructureAndStripsNoise() {
        String html = """
                <html><head><title>示例页</title><style>.a{}</style></head>
                <body>
                <nav><a href="/nav">导航</a></nav>
                <h1>主标题会被 title 替代</h1>
                <h2>章节一</h2>
                <p>这是<b>加粗</b>段落，见 <a href="https://example.com/link">链接</a>。</p>
                <pre><code>SELECT 1;</code></pre>
                <ul><li>项目 A</li><li>项目 B</li></ul>
                <script>alert(1)</script>
                </body></html>
                """;
        String markdown = HtmlToMarkdown.convert(html, "https://example.com/page");
        assertTrue(markdown.contains("# 示例页"));
        assertTrue(markdown.contains("来源：https://example.com/page"));
        assertTrue(markdown.contains("### 章节一"));
        assertTrue(markdown.contains("[链接](https://example.com/link)"));
        assertTrue(markdown.contains("- 项目 A"));
        assertTrue(markdown.contains("SELECT 1;"));
        assertFalse(markdown.contains("alert"));
        assertFalse(markdown.contains("导航"));
    }

    @Test
    void fallsBackToPlainTextForTinyOutput() {
        String markdown = HtmlToMarkdown.convert("<html><body><div>只有一句话的正文内容</div></body></html>", null);
        assertTrue(markdown.contains("只有一句话的正文内容"));
    }
}
