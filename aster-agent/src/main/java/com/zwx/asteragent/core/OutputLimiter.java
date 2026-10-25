package com.zwx.asteragent.core;

/**
 * 工具输出统一限额：超出 maxChars 截断并附加提示，避免撑爆客户端上下文。
 */
public final class OutputLimiter {

    private OutputLimiter() {
    }

    public static String limit(String content, int maxChars) {
        if (content == null) return "";
        if (content.length() <= maxChars) return content;
        return content.substring(0, maxChars)
                + "\n\n[输出已截断：原始 " + content.length() + " 字符 → " + maxChars
                + " 字符；可通过 aster.output.max-chars 调整，或收窄查询范围]";
    }
}
