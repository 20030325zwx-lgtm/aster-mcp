package com.zwx.asteragent.core;

/**
 * 工具错误约定：所有工具失败时返回 "TOOL_ERROR: <原因与指引>" 字符串（而非抛异常），
 * 与主项目 SEARCH_UNAVAILABLE 风格一致——让模型能读到原因并自行调整。
 */
public final class ToolError {

    private ToolError() {
    }

    public static String of(String reason) {
        return "TOOL_ERROR: " + reason;
    }

    public static String of(Throwable throwable) {
        String message = throwable.getMessage();
        return "TOOL_ERROR: " + (message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message.replaceAll("[\\r\\n]", " "));
    }

    public static boolean isError(String result) {
        return result != null && result.startsWith("TOOL_ERROR:");
    }
}
