package com.zwx.asteragent.core;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * db_query 只读护栏：
 * <ol>
 *   <li>单语句（分号只允许出现在末尾）；</li>
 *   <li>首词白名单 SELECT / WITH / EXPLAIN / SHOW / TABLE；</li>
 *   <li>全文拒绝任何写操作/DDL/会话管理关键字（含 CTE 内的写子句）；</li>
 *   <li>SELECT/WITH 无 LIMIT 时自动包一层 LIMIT，防止意外拉全表。</li>
 * </ol>
 */
public final class SqlGuard {

    private static final Pattern FIRST_WORD = Pattern.compile("^\\s*(SELECT|WITH|EXPLAIN|SHOW|TABLE)\\b.*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(INSERT|UPDATE|DELETE|MERGE|CREATE|ALTER|DROP|TRUNCATE|GRANT|REVOKE|COPY|CALL|DO|SET|"
                    + "REINDEX|VACUUM|ANALYZE|REFRESH|CLUSTER|CHECKPOINT|LISTEN|NOTIFY|LOCK|LISTEN|IMPORT)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LIMIT_PRESENT = Pattern.compile("\\bLIMIT\\s+\\d+", Pattern.CASE_INSENSITIVE);

    private SqlGuard() {
    }

    /** 校验语句；不合法抛 IllegalArgumentException（信息直接面向调用方）。 */
    public static String validate(String sql) {
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("SQL 不能为空");
        if (sql.indexOf('\0') >= 0) throw new IllegalArgumentException("SQL 含非法字符");
        String trimmed = sql.trim();
        String withoutTrailingSemicolon = trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1).trim() : trimmed;
        if (withoutTrailingSemicolon.contains(";")) {
            throw new IllegalArgumentException("仅允许单条语句（检测到多个分号）");
        }
        if (!FIRST_WORD.matcher(withoutTrailingSemicolon).matches()) {
            throw new IllegalArgumentException("仅允许只读查询（SELECT / WITH / EXPLAIN / SHOW / TABLE 开头）");
        }
        if (FORBIDDEN.matcher(withoutTrailingSemicolon).find()) {
            throw new IllegalArgumentException("只读护栏：检测到写操作或 DDL 关键字，已拒绝");
        }
        return withoutTrailingSemicolon;
    }

    /** SELECT / WITH 语句若无 LIMIT，包一层子查询限制行数；EXPLAIN/SHOW/TABLE 原样返回。 */
    public static String enforceLimit(String validatedSql, int maxRows) {
        String first = firstWord(validatedSql);
        if (!first.equals("SELECT") && !first.equals("WITH")) return validatedSql;
        if (LIMIT_PRESENT.matcher(validatedSql).find()) return validatedSql;
        return "SELECT * FROM (" + validatedSql + ") AS aster_limited LIMIT " + Math.max(1, maxRows);
    }

    private static String firstWord(String sql) {
        String trimmed = sql.stripLeading();
        int end = 0;
        while (end < trimmed.length() && Character.isLetter(trimmed.charAt(end))) end++;
        return trimmed.substring(0, end).toUpperCase(Locale.ROOT);
    }
}
