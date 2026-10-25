package com.zwx.asteragent.tools.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zwx.asteragent.ToolboxProperties;
import com.zwx.asteragent.core.OutputLimiter;
import com.zwx.asteragent.core.SqlGuard;
import com.zwx.asteragent.core.ToolError;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * db 组（Postgres/Supabase 位）：只读 SQL 查询 + 表结构探查。
 * 数据源由环境变量配置（ASTER_DB_URL/USER/PASSWORD），未配置时工具返回清晰指引。
 * 语句级护栏见 {@link SqlGuard}：单语句、白名单、禁写、自动 LIMIT、超时。
 */
public class DbTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_COLUMNS = 32;

    private final ToolboxProperties.Db props;
    private final int maxChars;
    private volatile HikariDataSource dataSource;

    public DbTool(ToolboxProperties.Db props, int maxChars) {
        this.props = props;
        this.maxChars = maxChars;
    }

    @Tool(name = "db_schema", description = "探查数据库表结构：列出 public schema 下匹配表的列名/类型/可空性。"
            + "写 SQL 前先用它确认字段名，避免猜列名。")
    public String schema(@ToolParam(description = "可选：表名过滤，支持 LIKE 通配（%/_），留空列出全部表", required = false) String tablePattern) {
        HikariDataSource source = source();
        if (source == null) return notConfigured();
        String pattern = tablePattern == null || tablePattern.isBlank() ? "%" : tablePattern.trim();
        try (Connection connection = source.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT table_name, column_name, data_type, is_nullable
                    FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name LIKE ?
                    ORDER BY table_name, ordinal_position""")) {
                statement.setString(1, pattern);
                try (ResultSet resultSet = statement.executeQuery()) {
                    Map<String, ArrayNode> tables = new LinkedHashMap<>();
                    while (resultSet.next()) {
                        String table = resultSet.getString(1);
                        ArrayNode columns = tables.computeIfAbsent(table, key -> MAPPER.createArrayNode());
                        ObjectNode column = columns.addObject();
                        column.put("column", resultSet.getString(2));
                        column.put("type", resultSet.getString(3));
                        column.put("nullable", "YES".equals(resultSet.getString(4)));
                    }
                    if (tables.isEmpty()) return "没有匹配的表（pattern=" + pattern + "）";
                    ObjectNode out = MAPPER.createObjectNode();
                    tables.forEach(out::putPOJO);
                    return OutputLimiter.limit(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out), maxChars);
                }
            }
        } catch (Exception e) {
            return ToolError.of(e);
        }
    }

    @Tool(name = "db_query", description = "对已配置的 PostgreSQL 执行只读查询（SELECT/WITH/EXPLAIN/SHOW），"
            + "返回 JSON 行。强制单语句、禁止写操作与 DDL、无 LIMIT 自动包一层 LIMIT、语句级超时。")
    public String query(@ToolParam(description = "只读 SQL 语句") String sql) {
        HikariDataSource source = source();
        if (source == null) return notConfigured();
        String validated;
        try {
            validated = SqlGuard.enforceLimit(SqlGuard.validate(sql), props.getMaxRows());
        } catch (IllegalArgumentException e) {
            return ToolError.of(e.getMessage());
        }
        try (Connection connection = source.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(validated)) {
                statement.setQueryTimeout(props.getTimeoutSeconds());
                statement.setMaxRows(props.getMaxRows() + 1);
                try (ResultSet resultSet = statement.executeQuery()) {
                    ResultSetMetaData meta = resultSet.getMetaData();
                    int columnCount = Math.min(meta.getColumnCount(), MAX_COLUMNS);
                    ArrayNode rows = MAPPER.createArrayNode();
                    while (resultSet.next() && rows.size() < props.getMaxRows()) {
                        ObjectNode row = rows.addObject();
                        for (int i = 1; i <= columnCount; i++) {
                            Object value = resultSet.getObject(i);
                            row.putPOJO(meta.getColumnLabel(i), value == null ? null : String.valueOf(value));
                        }
                    }
                    ObjectNode out = MAPPER.createObjectNode();
                    out.put("rowCount", rows.size());
                    out.put("truncated", rows.size() >= props.getMaxRows());
                    out.set("rows", rows);
                    return OutputLimiter.limit(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out), maxChars);
                }
            }
        } catch (Exception e) {
            return ToolError.of(e);
        }
    }

    /** 惰性建池；未配置 url 返回 null（工具给指引而不是崩溃）。 */
    private HikariDataSource source() {
        if (props.getUrl() == null || props.getUrl().isBlank()) return null;
        if (dataSource == null) {
            synchronized (this) {
                if (dataSource == null) {
                    HikariConfig config = new HikariConfig();
                    config.setJdbcUrl(props.getUrl());
                    config.setUsername(props.getUsername());
                    config.setPassword(props.getPassword());
                    config.setMaximumPoolSize(4);
                    config.setReadOnly(true);
                    config.setPoolName("aster-db-tool");
                    dataSource = new HikariDataSource(config);
                }
            }
        }
        return dataSource;
    }

    private String notConfigured() {
        return ToolError.of("db 组未配置数据源。启动时设置环境变量：ASTER_DB_URL（jdbc:postgresql://host:5432/db）、"
                + "ASTER_DB_USER、ASTER_DB_PASSWORD 后即可使用；或用 aster.tools.db.enabled=false 整组下线。"
                + "（提示：主项目本地库形如 jdbc:postgresql://127.0.0.1:5432/<库名>，凭据见 yu-ai-agent 的 application-local.yml）");
    }

    public void shutdown() {
        if (dataSource != null) dataSource.close();
    }
}
