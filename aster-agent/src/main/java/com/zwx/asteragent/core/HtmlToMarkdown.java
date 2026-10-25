package com.zwx.asteragent.core;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

/**
 * HTML → Markdown：web_fetch / docs 组共用。剔除脚本样式导航等噪声，
 * 保留标题/段落/链接/列表/代码块/表格的基本结构。
 */
public final class HtmlToMarkdown {

    private HtmlToMarkdown() {
    }

    public static String convert(String html, String sourceUrl) {
        Document document = Jsoup.parse(html);
        document.select("script,style,noscript,nav,footer,header,aside,form,svg,iframe,template").remove();
        String title = document.title() == null ? "" : document.title().trim();
        Element body = document.body();
        if (body == null) return "# " + title;

        StringBuilder out = new StringBuilder();
        if (!title.isEmpty()) out.append("# ").append(title).append("\n\n");
        if (sourceUrl != null && !sourceUrl.isBlank()) out.append("> 来源：").append(sourceUrl).append("\n\n");
        NodeTraversor.traverse(new Visitor(out), body);
        String markdown = out.toString().replaceAll("\\n{3,}", "\n\n").trim();
        if (markdown.length() < 30) {
            String plain = body.text();
            markdown = plain.isBlank() ? markdown : plain;
        }
        return markdown;
    }

    private static final class Visitor implements NodeVisitor {
        private final StringBuilder out;

        Visitor(StringBuilder out) {
            this.out = out;
        }

        @Override
        public void head(Node node, int depth) {
            if (node instanceof TextNode text) {
                String value = text.getWholeText().replaceAll("\\s+", " ");
                if (!value.isBlank()) out.append(value);
                return;
            }
            if (!(node instanceof Element element)) return;
            switch (element.tagName()) {
                case "h1" -> newline(2).append("## ");
                case "h2" -> newline(2).append("### ");
                case "h3", "h4", "h5", "h6" -> newline(2).append("#### ");
                case "p", "div", "section", "article" -> newline(2);
                case "br" -> out.append('\n');
                case "li" -> newline(1).append("- ");
                case "pre" -> newline(2).append("```\n");
                case "code" -> out.append('`');
                case "blockquote" -> newline(2).append("> ");
                case "tr" -> newline(1).append("| ");
                case "th", "td" -> out.append(" | ");
                case "a" -> out.append('[');
                case "img" -> {
                    String alt = element.attr("alt");
                    if (!alt.isBlank()) out.append("![📷 图片：").append(alt).append(']');
                }
                default -> { }
            }
        }

        @Override
        public void tail(Node node, int depth) {
            if (!(node instanceof Element element)) return;
            switch (element.tagName()) {
                case "h1", "h2", "h3", "h4", "h5", "h6" -> newline(2);
                case "p", "div", "section", "article", "blockquote" -> newline(2);
                case "li" -> newline(1);
                case "pre" -> out.append("\n```");
                case "code" -> out.append('`');
                case "tr" -> out.append(" |");
                case "a" -> {
                    String href = element.absUrl("href");
                    if (!href.isEmpty()) out.append("](").append(href).append(")");
                    else out.append("]");
                }
                default -> { }
            }
        }

        private StringBuilder newline(int count) {
            while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') out.setLength(out.length() - 1);
            out.append("\n".repeat(count));
            return out;
        }
    }
}
