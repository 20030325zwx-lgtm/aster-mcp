package com.zwx.asteragent;

import com.zwx.asteragent.core.AuditingToolCallback;
import com.zwx.asteragent.core.FsSandbox;
import com.zwx.asteragent.core.ToolAudit;
import com.zwx.asteragent.core.UrlGuard;
import com.zwx.asteragent.tools.browser.BrowserSession;
import com.zwx.asteragent.tools.browser.BrowserTool;
import com.zwx.asteragent.tools.db.DbTool;
import com.zwx.asteragent.tools.docs.DocsTool;
import com.zwx.asteragent.tools.fs.FsTool;
import com.zwx.asteragent.tools.github.GithubTool;
import com.zwx.asteragent.tools.kb.KbTool;
import com.zwx.asteragent.tools.kb.ToolboxCatalogTool;
import com.zwx.asteragent.tools.web.WebExtractLinksTool;
import com.zwx.asteragent.tools.web.WebFetchTool;
import com.zwx.asteragent.tools.web.WebSearchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * 工具装配中心：按配置开关逐组注册工具，统一包审计装饰器。
 * 组顺序即 tools/list 中的展示顺序：meta → web → docs → db → fs → github → browser → kb。
 */
@Configuration
public class ToolboxConfig {

    private static final Logger log = LoggerFactory.getLogger(ToolboxConfig.class);

    @Bean
    public ToolCallbackProvider asterToolbox(ToolboxProperties props) {
        int maxChars = props.getOutput().getMaxChars();
        UrlGuard urlGuard = new UrlGuard(props.getSecurity().isAllowPrivateEndpoints(),
                props.getSecurity().getAllowHosts(),
                host -> {
                    try {
                        return InetAddress.getAllByName(host);
                    } catch (java.net.UnknownHostException e) {
                        throw new IllegalArgumentException("主机名无法解析：" + host, e);
                    }
                });

        List<Object> objects = new ArrayList<>();
        objects.add(new ToolboxCatalogTool(props));

        ToolboxProperties.Tools tools = props.getTools();
        WebFetchTool fetchTool = null;
        if (tools.getWeb().isEnabled()) {
            objects.add(new WebSearchTool(tools.getWeb(), maxChars));
            fetchTool = new WebFetchTool(tools.getWeb(), urlGuard, maxChars);
            objects.add(fetchTool);
            objects.add(new WebExtractLinksTool(tools.getWeb(), urlGuard));
            log.info("web 组已启用（provider={}）", tools.getWeb().getProvider());
        }
        if (tools.getDocs().isEnabled()) {
            objects.add(new DocsTool(tools.getDocs(), fetchTool, maxChars));
            log.info("docs 组已启用（context7={}）", tools.getDocs().getContext7BaseUrl());
        }
        if (tools.getDb().isEnabled()) {
            DbTool dbTool = new DbTool(tools.getDb(), maxChars);
            objects.add(dbTool);
            Runtime.getRuntime().addShutdownHook(new Thread(dbTool::shutdown, "aster-db-shutdown"));
            log.info("db 组已启用（url {}）", tools.getDb().getUrl() == null || tools.getDb().getUrl().isBlank()
                    ? "未配置（工具将返回指引）" : "已配置");
        }
        if (tools.getFs().isEnabled()) {
            FsSandbox sandbox = new FsSandbox(tools.getFs().getRoot());
            objects.add(new FsTool(sandbox, tools.getFs(), maxChars));
            log.info("fs 组已启用（沙箱根 {}，{}）", sandbox.root(),
                    tools.getFs().isAllowWrite() ? "可写" : "只读");
        }
        if (tools.getGithub().isEnabled()) {
            objects.add(new GithubTool(tools.getGithub(), maxChars));
            log.info("github 组已启用（token {}）",
                    tools.getGithub().getToken() == null || tools.getGithub().getToken().isBlank() ? "匿名" : "已配置");
        }
        if (tools.getBrowser().isEnabled()) {
            BrowserSession session = new BrowserSession(tools.getBrowser());
            objects.add(new BrowserTool(session, urlGuard, maxChars));
            log.info("browser 组已启用（headless Chrome 按需拉起）");
        }
        if (tools.getKb().isEnabled()) {
            objects.add(new KbTool(tools.getKb(), maxChars));
            log.info("kb 组已启用（base-url={}，agentKey={}）", tools.getKb().getBaseUrl(), tools.getKb().getAgentKey());
        }

        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(objects.toArray())
                .build()
                .getToolCallbacks();
        ToolAudit audit = new ToolAudit(props.getAudit().isEnabled(), props.getAudit().getLogFile());
        ToolCallback[] wrapped = new ToolCallback[callbacks.length];
        for (int i = 0; i < callbacks.length; i++) {
            wrapped[i] = new AuditingToolCallback(callbacks[i], audit);
        }
        log.info("aster-agent 工具箱装配完成：{} 个工具", wrapped.length);
        return () -> wrapped;
    }
}
