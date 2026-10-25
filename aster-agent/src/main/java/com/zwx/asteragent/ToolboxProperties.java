package com.zwx.asteragent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工具箱全部配置（aster.*）。所有环境差异都通过 env 覆盖，代码不感知部署形态。
 */
@ConfigurationProperties(prefix = "aster")
public class ToolboxProperties {

    private final Output output = new Output();
    private final Audit audit = new Audit();
    private final Security security = new Security();
    private final Tools tools = new Tools();

    public Output getOutput() { return output; }
    public Audit getAudit() { return audit; }
    public Security getSecurity() { return security; }
    public Tools getTools() { return tools; }

    public static class Output {
        private int maxChars = 24000;
        public int getMaxChars() { return maxChars; }
        public void setMaxChars(int maxChars) { this.maxChars = maxChars; }
    }

    public static class Audit {
        private boolean enabled = true;
        private String logFile = "logs/audit.jsonl";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getLogFile() { return logFile; }
        public void setLogFile(String logFile) { this.logFile = logFile; }
    }

    public static class Security {
        /** true 时放行私网/环回地址（仅建议本机调试用）。 */
        private boolean allowPrivateEndpoints = false;
        /** 额外放行的主机名后缀，逗号分隔，例如 .internal.example.com。 */
        private String allowHosts = "";
        public boolean isAllowPrivateEndpoints() { return allowPrivateEndpoints; }
        public void setAllowPrivateEndpoints(boolean v) { this.allowPrivateEndpoints = v; }
        public String getAllowHosts() { return allowHosts; }
        public void setAllowHosts(String allowHosts) { this.allowHosts = allowHosts; }
    }

    public static class Tools {
        private final Web web = new Web();
        private final Docs docs = new Docs();
        private final Db db = new Db();
        private final Fs fs = new Fs();
        private final Github github = new Github();
        private final Browser browser = new Browser();
        private final Kb kb = new Kb();
        public Web getWeb() { return web; }
        public Docs getDocs() { return docs; }
        public Db getDb() { return db; }
        public Fs getFs() { return fs; }
        public Github getGithub() { return github; }
        public Browser getBrowser() { return browser; }
        public Kb getKb() { return kb; }
    }

    public static class Web {
        private boolean enabled = true;
        private String provider = "searchapi";
        private String apiKey = "";
        private String engine = "baidu";
        private boolean keylessFallback = true;
        private int timeoutMs = 15000;
        private int maxResults = 8;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getEngine() { return engine; }
        public void setEngine(String engine) { this.engine = engine; }
        public boolean isKeylessFallback() { return keylessFallback; }
        public void setKeylessFallback(boolean v) { this.keylessFallback = v; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getMaxResults() { return maxResults; }
        public void setMaxResults(int maxResults) { this.maxResults = maxResults; }
    }

    public static class Docs {
        private boolean enabled = true;
        private String context7BaseUrl = "https://context7.com";
        private boolean fallbackToFetch = true;
        private int timeoutMs = 15000;
        private int maxTokens = 5000;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getContext7BaseUrl() { return context7BaseUrl; }
        public void setContext7BaseUrl(String v) { this.context7BaseUrl = v; }
        public boolean isFallbackToFetch() { return fallbackToFetch; }
        public void setFallbackToFetch(boolean v) { this.fallbackToFetch = v; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    }

    public static class Db {
        private boolean enabled = true;
        private String url = "";
        private String username = "";
        private String password = "";
        private int maxRows = 200;
        private int timeoutSeconds = 20;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public int getMaxRows() { return maxRows; }
        public void setMaxRows(int maxRows) { this.maxRows = maxRows; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }

    public static class Fs {
        private boolean enabled = true;
        private String root = "./sandbox";
        private boolean allowWrite = false;
        private int maxReadBytes = 524288;
        private int maxWriteBytes = 262144;
        private int maxEntries = 500;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getRoot() { return root; }
        public void setRoot(String root) { this.root = root; }
        public boolean isAllowWrite() { return allowWrite; }
        public void setAllowWrite(boolean allowWrite) { this.allowWrite = allowWrite; }
        public int getMaxReadBytes() { return maxReadBytes; }
        public void setMaxReadBytes(int v) { this.maxReadBytes = v; }
        public int getMaxWriteBytes() { return maxWriteBytes; }
        public void setMaxWriteBytes(int v) { this.maxWriteBytes = v; }
        public int getMaxEntries() { return maxEntries; }
        public void setMaxEntries(int maxEntries) { this.maxEntries = maxEntries; }
    }

    public static class Github {
        private boolean enabled = true;
        private String token = "";
        private int timeoutMs = 15000;
        private int perPage = 10;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getPerPage() { return perPage; }
        public void setPerPage(int perPage) { this.perPage = perPage; }
    }

    public static class Browser {
        private boolean enabled = true;
        private String chromePath = "";
        private boolean headless = true;
        private int timeoutMs = 20000;
        private String screenshotDir = "sandbox/screenshots";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getChromePath() { return chromePath; }
        public void setChromePath(String chromePath) { this.chromePath = chromePath; }
        public boolean isHeadless() { return headless; }
        public void setHeadless(boolean headless) { this.headless = headless; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public String getScreenshotDir() { return screenshotDir; }
        public void setScreenshotDir(String screenshotDir) { this.screenshotDir = screenshotDir; }
    }

    public static class Kb {
        private boolean enabled = true;
        private String baseUrl = "http://127.0.0.1:8123/api";
        private String token = "";
        private String agentKey = "test";
        private int timeoutMs = 15000;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public String getAgentKey() { return agentKey; }
        public void setAgentKey(String agentKey) { this.agentKey = agentKey; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    }
}
