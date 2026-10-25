package com.zwx.asteragent.tools.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwx.asteragent.ToolboxProperties;
import com.zwx.asteragent.core.HttpSupport;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * headless Chrome 会话：通过 Chrome DevTools Protocol（WebSocket）实现 navigate / eval / screenshot。
 *
 * <p>实现约束：零外部依赖（java.net.http.WebSocket + Jackson）；Chrome 进程按需拉起、
 * JVM 退出时销毁；单页单飞（同一时刻只跑一个页面操作）。
 */
public class BrowserSession implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> CHROME_CANDIDATES = List.of(
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "/Applications/Chromium.app/Contents/MacOS/Chromium",
            "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
            "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser",
            "/usr/bin/google-chrome", "/usr/bin/chromium", "/usr/bin/chromium-browser");

    private final ToolboxProperties.Browser props;
    private final Semaphore singleFlight = new Semaphore(1, true);
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    private Process chrome;
    private Path userDataDir;
    private WebSocket websocket;

    public BrowserSession(ToolboxProperties.Browser props) {
        this.props = props;
        Runtime.getRuntime().addShutdownHook(new Thread(this::destroyQuietly, "aster-browser-shutdown"));
    }

    /** 打开（或复用）页面并等待加载完成，返回 title 与正文文本。 */
    public String navigate(String url, int maxChars) throws Exception {
        return withPage(() -> {
            send("Page.enable", Map.of());
            JsonNode nav = send("Page.navigate", Map.of("url", url));
            String errorText = nav.path("errorText").asText("");
            if (!errorText.isEmpty()) throw new IllegalArgumentException("导航失败：" + errorText);
            waitForLoad();
            return pageSummary(maxChars);
        });
    }

    /** 在当前页面执行 JS 表达式（支持 await Promise），返回值的字符串形式。 */
    public String evaluate(String url, String expression, int maxChars) throws Exception {
        return withPage(() -> {
            send("Page.enable", Map.of());
            if (url != null && !url.isBlank()) {
                send("Page.navigate", Map.of("url", url));
                waitForLoad();
            }
            Map<String, Object> params = new HashMap<>();
            params.put("expression", expression);
            params.put("returnByValue", true);
            params.put("awaitPromise", true);
            JsonNode result = send("Runtime.evaluate", params);
            if (result.has("exceptionDetails")) {
                JsonNode details = result.get("exceptionDetails");
                throw new IllegalArgumentException("JS 执行异常：" + details.path("text").asText("") + " "
                        + details.path("exception").path("description").asText(""));
            }
            JsonNode value = result.path("result").path("value");
            String text = value.isMissingNode()
                    ? result.path("result").path("type").asText("undefined") : value.asText("");
            return text.length() > maxChars ? text.substring(0, maxChars) + "\n[输出已截断]" : text;
        });
    }

    /** 截全页 PNG，保存到沙箱 screenshots 目录，返回文件路径。 */
    public String screenshot(String url) throws Exception {
        return withPage(() -> {
            send("Page.enable", Map.of());
            send("Page.navigate", Map.of("url", url));
            waitForLoad();
            JsonNode shot = send("Page.captureScreenshot", Map.of("format", "png"));
            byte[] bytes = Base64.getDecoder().decode(shot.path("data").asText());
            Path dir = Path.of(props.getScreenshotDir()).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Path file = dir.resolve("shot-" + System.currentTimeMillis() + ".png");
            Files.write(file, bytes);
            return file.toString() + "（" + bytes.length + " 字节）";
        });
    }

    // ---------- 内部：CDP 会话管理 ----------

    private interface CdpCall<T> {
        T invoke() throws Exception;
    }

    private <T> T withPage(CdpCall<T> call) throws Exception {
        singleFlight.acquire();
        try {
            ensureStarted();
            return call.invoke();
        } finally {
            singleFlight.release();
        }
    }

    private synchronized void ensureStarted() throws IOException {
        if (websocket != null) return;
        String chromeBinary = props.getChromePath() == null || props.getChromePath().isBlank()
                ? CHROME_CANDIDATES.stream().filter(path -> Files.isExecutable(Path.of(path))).findFirst().orElse(null)
                : props.getChromePath();
        if (chromeBinary == null) {
            throw new IllegalArgumentException("未找到 Chrome/Chromium/Edge：请安装 Google Chrome，"
                    + "或通过环境变量 CHROME_PATH 指定可执行文件路径");
        }
        int port = freePort();
        userDataDir = Files.createTempDirectory("aster-chrome-");
        List<String> command = new ArrayList<>(List.of(chromeBinary,
                "--remote-debugging-port=" + port,
                "--user-data-dir=" + userDataDir,
                "--no-first-run", "--no-default-browser-check", "--disable-gpu", "--disable-extensions",
                "--remote-allow-origins=*"));
        if (props.isHeadless()) command.add("--headless=new");
        try {
            chrome = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new IOException("Chrome 启动失败（" + chromeBinary + "）：" + e.getMessage(), e);
        }

        String version = null;
        IOException lastError = null;
        for (int i = 0; i < 60 && version == null; i++) {
            try {
                version = HttpSupport.get("http://127.0.0.1:" + port + "/json/version", 1000);
            } catch (Exception e) {
                lastError = new IOException("Chrome DevTools 端口未就绪", e);
                sleep(250);
            }
        }
        if (version == null) {
            destroyQuietly();
            throw new IOException("Chrome 启动超时：" + (lastError == null ? "" : lastError.getMessage()));
        }
        String pageWsUrl = findPageTargetUrl(port);
        if (pageWsUrl == null) {
            destroyQuietly();
            throw new IOException("Chrome 未提供可用的 page target");
        }
        try {
            websocket = HttpSupport.client()
                    .newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(pageWsUrl), new CdpListener())
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            destroyQuietly();
            throw new IOException("CDP WebSocket 连接失败：" + e.getMessage(), e);
        }
    }

    private String findPageTargetUrl(int port) {
        try {
            JsonNode targets = MAPPER.readTree(HttpSupport.get("http://127.0.0.1:" + port + "/json/list", 2000));
            if (targets.isArray()) {
                for (JsonNode target : targets) {
                    if ("page".equals(target.path("type").asText())
                            && target.hasNonNull("webSocketDebuggerUrl")) {
                        return target.get("webSocketDebuggerUrl").asText();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private JsonNode send(String method, Map<String, Object> params) throws Exception {
        long id = nextId.getAndIncrement();
        Map<String, Object> message = new HashMap<>();
        message.put("id", id);
        message.put("method", method);
        message.put("params", params == null ? Map.of() : params);
        CompletableFuture<JsonNode> future = new CompletableFuture<JsonNode>()
                .orTimeout(props.getTimeoutMs(), TimeUnit.MILLISECONDS)
                .whenComplete((result, error) -> pending.remove(id));
        pending.put(id, future);
        websocket.sendText(MAPPER.writeValueAsString(message), true);
        JsonNode response = future.join();
        if (response.has("error")) {
            throw new IllegalArgumentException("CDP 错误（" + method + "）："
                    + response.path("error").path("message").asText());
        }
        return response.path("result");
    }

    /** Page.navigate 后轮询 document.readyState，直到 complete 或超时（超时不视为失败，返回已加载内容）。 */
    private void waitForLoad() throws Exception {
        long deadline = System.currentTimeMillis() + props.getTimeoutMs();
        while (System.currentTimeMillis() < deadline) {
            JsonNode result = send("Runtime.evaluate",
                    Map.of("expression", "document.readyState", "returnByValue", true));
            if ("complete".equals(result.path("result").path("value").asText())) return;
            sleep(300);
        }
    }

    private String pageSummary(int maxChars) throws Exception {
        JsonNode result = send("Runtime.evaluate", Map.of(
                "expression", "JSON.stringify({title: document.title, url: location.href, "
                        + "text: (document.body ? document.body.innerText : '').slice(0, " + maxChars + ")})",
                "returnByValue", true));
        String raw = result.path("result").path("value").asText("");
        try {
            JsonNode summary = MAPPER.readTree(raw);
            return "# " + summary.path("title").asText("(无标题)") + "\n> " + summary.path("url").asText("") + "\n\n"
                    + summary.path("text").asText("(空页面)");
        } catch (Exception e) {
            return raw;
        }
    }

    private final class CdpListener implements WebSocket.Listener {
        private final List<String> fragments = new ArrayList<>();

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            fragments.add(data.toString());
            if (last) {
                String whole = String.join("", fragments);
                fragments.clear();
                try {
                    JsonNode message = MAPPER.readTree(whole);
                    if (message.has("id")) {
                        CompletableFuture<JsonNode> future = pending.remove(message.get("id").asLong());
                        if (future != null) future.complete(message);
                    }
                    // CDP 事件（loadEventFired 等）按当前策略不需要消费
                } catch (Exception ignored) {
                }
            }
            socket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            pending.forEach((id, future) -> future.completeExceptionally(error));
            pending.clear();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void destroyQuietly() {
        if (websocket != null) {
            try {
                websocket.abort();
            } catch (Exception ignored) {
            }
            websocket = null;
        }
        if (chrome != null && chrome.isAlive()) {
            chrome.destroyForcibly();
        }
        chrome = null;
        if (userDataDir != null) {
            try (var paths = Files.walk(userDataDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
            } catch (Exception ignored) {
            }
            userDataDir = null;
        }
    }

    @Override
    public void close() {
        destroyQuietly();
    }
}
