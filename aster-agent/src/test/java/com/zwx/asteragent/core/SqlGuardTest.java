package com.zwx.asteragent.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlGuardTest {

    @Test
    void allowsReadOnlyStatements() {
        assertDoesNotThrow(() -> SqlGuard.validate("SELECT 1"));
        assertDoesNotThrow(() -> SqlGuard.validate(" select * from t where id = 1; "));
        assertDoesNotThrow(() -> SqlGuard.validate("WITH x AS (SELECT 1) SELECT * FROM x"));
        assertDoesNotThrow(() -> SqlGuard.validate("EXPLAIN SELECT * FROM t"));
        assertDoesNotThrow(() -> SqlGuard.validate("SHOW server_version"));
    }

    @Test
    void rejectsWriteAndDdl() {
        assertThrows(IllegalArgumentException.class, () -> SqlGuard.validate("DELETE FROM t"));
        assertThrows(IllegalArgumentException.class, () -> SqlGuard.validate("UPDATE t SET a=1"));
        assertThrows(IllegalArgumentException.class, () -> SqlGuard.validate("INSERT INTO t VALUES (1)"));
        assertThrows(IllegalArgumentException.class, () -> SqlGuard.validate("DROP TABLE t"));
        assertThrows(IllegalArgumentException.class, () -> SqlGuard.validate("SELECT * FROM t; DROP TABLE t"));
        // CTE 内的写子句也要拦
        assertThrows(IllegalArgumentException.class,
                () -> SqlGuard.validate("WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x"));
    }

    @Test
    void rejectsMultipleStatementsAndEmpty() {
        assertThrows(IllegalArgumentException.class, () -> SqlGuard.validate(""));
        assertThrows(IllegalArgumentException.class, () -> SqlGuard.validate("SELECT 1; SELECT 2"));
        assertThrows(IllegalArgumentException.class, () -> SqlGuard.validate("SELECT 1\0"));
    }

    @Test
    void addsLimitWhenMissing() {
        assertEquals("SELECT * FROM (SELECT * FROM t) AS aster_limited LIMIT 200",
                SqlGuard.enforceLimit("SELECT * FROM t", 200));
        // 已有 LIMIT 原样保留
        assertEquals("SELECT * FROM t LIMIT 5", SqlGuard.enforceLimit("SELECT * FROM t LIMIT 5", 200));
        // EXPLAIN 不包 LIMIT
        assertEquals("EXPLAIN SELECT * FROM t", SqlGuard.enforceLimit("EXPLAIN SELECT * FROM t", 200));
        assertTrue(SqlGuard.enforceLimit("WITH x AS (SELECT 1) SELECT * FROM x", 50).contains("aster_limited"));
    }
}
