package com.zwx.asteragent.core;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlGuardTest {

    /** 不做真实 DNS：直接把主机映射为给定地址，专注护栏逻辑。 */
    private UrlGuard guard(boolean allowPrivate, String allowHosts, String fakeIp) {
        return new UrlGuard(allowPrivate, allowHosts, host -> {
            try {
                return new InetAddress[]{InetAddress.getByAddress(fakeIp == null ? host : host, parse(fakeIp))};
            } catch (java.net.UnknownHostException e) {
                throw new IllegalArgumentException(e);
            }
        });
    }

    private static byte[] parse(String ip) {
        String[] parts = ip.split("\\.");
        return new byte[]{(byte) Integer.parseInt(parts[0]), (byte) Integer.parseInt(parts[1]),
                (byte) Integer.parseInt(parts[2]), (byte) Integer.parseInt(parts[3])};
    }

    @Test
    void allowsPublicHttpUrls() {
        assertDoesNotThrow(() -> guard(false, "", "93.184.216.34").validate("https://example.com/docs"));
        assertDoesNotThrow(() -> guard(false, "", "93.184.216.34").validate("http://example.com:8080/x"));
    }

    @Test
    void rejectsPrivateAndLoopback() {
        assertThrows(IllegalArgumentException.class, () -> guard(false, "", "10.1.2.3").validate("http://internal.corp/api"));
        assertThrows(IllegalArgumentException.class, () -> guard(false, "", "192.168.1.1").validate("http://router.home/"));
        assertThrows(IllegalArgumentException.class, () -> guard(false, "", "172.16.0.1").validate("http://intranet/"));
        assertThrows(IllegalArgumentException.class, () -> guard(false, "", "100.64.0.1").validate("http://cgnat/"));
        assertThrows(IllegalArgumentException.class, () -> guard(false, "", "169.254.1.1").validate("http://metadata/"));
    }

    @Test
    void rejectsLocalhostNamesAndNonHttpSchemes() {
        UrlGuard guard = guard(false, "", "93.184.216.34");
        assertThrows(IllegalArgumentException.class, () -> guard.validate("http://localhost:8080/"));
        assertThrows(IllegalArgumentException.class, () -> guard.validate("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> guard.validate("ftp://example.com/x"));
        assertThrows(IllegalArgumentException.class, () -> guard.validate("not a url"));
    }

    @Test
    void allowlistAndSwitchOverride() {
        assertDoesNotThrow(() -> guard(true, "", "10.0.0.5").validate("http://internal.corp/api"));
        assertDoesNotThrow(() -> guard(false, ".corp", "10.0.0.5").validate("http://intranet.corp/api"));
    }

    @Test
    void truncationNotice() {
        String limited = OutputLimiter.limit("a".repeat(100), 10);
        assertTrue(limited.startsWith("aaaaaaaaaa"));
        assertTrue(limited.contains("输出已截断"));
    }
}
