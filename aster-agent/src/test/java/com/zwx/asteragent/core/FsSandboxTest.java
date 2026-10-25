package com.zwx.asteragent.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FsSandboxTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsEscapeAttempts() {
        FsSandbox sandbox = new FsSandbox(tempDir.resolve("box").toString());
        assertThrows(IllegalArgumentException.class, () -> sandbox.resolve("../outside.txt"));
        assertThrows(IllegalArgumentException.class, () -> sandbox.resolve("a/../../outside.txt"));
        assertThrows(IllegalArgumentException.class, () -> sandbox.resolve("/etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> sandbox.resolve(""));
        // 正常相对路径可用
        sandbox.resolve("notes/a.txt");
    }

    @Test
    void readRespectsSizeCap() throws IOException {
        FsSandbox sandbox = new FsSandbox(tempDir.resolve("box2").toString());
        Path file = sandbox.resolve("big.txt");
        Files.writeString(file, "x".repeat(100));
        assertThrows(IllegalArgumentException.class, () -> sandbox.readString(file, 10));
        assertTrue(sandbox.readString(file, 200).length() == 100);
    }

    @Test
    void writeRespectsSizeCap() throws IOException {
        FsSandbox sandbox = new FsSandbox(tempDir.resolve("box3").toString());
        Path file = sandbox.resolve("out/notes.md");
        sandbox.writeString(file, "hello", 100);
        assertTrue(Files.exists(file));
        assertThrows(IllegalArgumentException.class, () -> sandbox.writeString(file, "y".repeat(50), 10));
    }
}
