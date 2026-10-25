package com.zwx.asteragent.core;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 出站 HTTP 支撑：共享连接池、统一 UA/超时/错误映射。所有工具组共用。
 */
public final class HttpSupport {

    public static final String USER_AGENT = "aster-agent-mcp/0.0.1 (+https://127.0.0.1:8124)";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private HttpSupport() {
    }

    public static HttpClient client() {
        return CLIENT;
    }

    public static String get(String url, int timeoutMs, String... headers) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("User-Agent", USER_AGENT)
                    .GET();
            for (int i = 0; i + 1 < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
            HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalArgumentException("HTTP " + response.statusCode() + "（" + brief(response.body()) + "）");
            }
            return response.body();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("请求失败：" + e.getMessage());
        }
    }

    public static String post(String url, String jsonBody, int timeoutMs, String... headers) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("User-Agent", USER_AGENT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody));
            for (int i = 0; i + 1 < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
            HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalArgumentException("HTTP " + response.statusCode() + "（" + brief(response.body()) + "）");
            }
            return response.body();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("请求失败：" + e.getMessage());
        }
    }

    private static String brief(String body) {
        if (body == null) return "";
        String flat = body.replaceAll("\\s+", " ");
        return flat.length() > 200 ? flat.substring(0, 200) : flat;
    }
}
