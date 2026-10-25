package com.zwx.asteragent.core;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.function.Function;

/**
 * SSRF 防护：web / docs / browser 组访问任意 URL 前必须过此校验。
 *
 * <p>规则：仅允许 http/https；拒绝环回、私网（RFC1918）、链路本地、ULA、组播及
 * 0.0.0.0 等特殊地址；支持主机名后缀白名单与全局私网放行开关（调试用）。
 * kb 组访问的是管理员显式配置的 base-url，不属于「任意 URL」，不经过本类。
 */
public class UrlGuard {

    private final boolean allowPrivate;
    private final List<String> allowHostSuffixes;
    private final Function<String, InetAddress[]> resolver;

    public UrlGuard(boolean allowPrivate, String allowHostsCsv, Function<String, InetAddress[]> resolver) {
        this.allowPrivate = allowPrivate;
        this.allowHostSuffixes = allowHostsCsv == null ? List.of()
                : java.util.Arrays.stream(allowHostsCsv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.resolver = resolver;
    }

    public static UrlGuard strict() {
        return new UrlGuard(false, "", host -> {
            throw new IllegalArgumentException("DNS 解析不可用");
        });
    }

    /** 校验并返回规范化的 URL；不合法时抛出带中文说明的 IllegalArgumentException。 */
    public String validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) throw new IllegalArgumentException("URL 不能为空");
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("URL 格式不正确：" + rawUrl);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("仅允许 http/https URL，拒绝：" + scheme);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new IllegalArgumentException("URL 缺少主机名");
        if (host.endsWith(".local") || host.equalsIgnoreCase("localhost") || host.endsWith(".localhost")) {
            if (allowPrivate || isAllowed(host)) return uri.toString();
            throw new IllegalArgumentException("拒绝访问本地/内网地址：" + host + "（可在 aster.security 配置放行）");
        }
        if (allowPrivate || isAllowed(host)) return uri.toString();
        // 字面 IP 主机：全量拦截（含 ULA）——攻击者可直接写内网地址
        if (host.indexOf(':') >= 0 || host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            InetAddress[] literals;
            try {
                literals = resolver.apply(host);
            } catch (Exception e) {
                throw new IllegalArgumentException("IP 地址无法解析：" + host);
            }
            if (literals == null || literals.length == 0) throw new IllegalArgumentException("IP 地址无法解析：" + host);
            for (InetAddress address : literals) {
                if (isPrivateAddress(address)) {
                    throw new IllegalArgumentException("拒绝访问内网/保留地址 " + address.getHostAddress()
                            + "（SSRF 防护；可在 aster.security 放行）");
                }
            }
            return uri.toString();
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.apply(host);
        } catch (Exception e) {
            throw new IllegalArgumentException("主机名无法解析：" + host);
        }
        if (addresses == null || addresses.length == 0) throw new IllegalArgumentException("主机名无法解析：" + host);
        for (InetAddress address : addresses) {
            // 域名解析路径用收窄规则：放行 ULA（fc00::/7），因为部分本机代理（fake-IP DNS）
            // 会把公网域名解析为 ULA 段；环回/链路本地/RFC1918/CGNAT 仍然拦截。
            if (isPrivateAddress(address, false)) {
                throw new IllegalArgumentException("目标主机解析到内网/保留地址 " + address.getHostAddress()
                        + "，已拒绝（SSRF 防护；可在 aster.security 放行）");
            }
        }
        return uri.toString();
    }

    private boolean isAllowed(String host) {
        String lower = host.toLowerCase();
        return allowHostSuffixes.stream().anyMatch(suffix -> lower.equals(suffix) || lower.endsWith(suffix.startsWith(".") ? suffix : "." + suffix));
    }

    static boolean isPrivateAddress(InetAddress address) {
        return isPrivateAddress(address, true);
    }

    /** strict=true 拦截 ULA（字面 IP 全量）；strict=false 放行 ULA（域名解析路径，兼容 fake-IP DNS）。 */
    static boolean isPrivateAddress(InetAddress address, boolean strict) {
        if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress() || address.isAnyLocalAddress()) return true;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int b0 = bytes[0] & 0xFF, b1 = bytes[1] & 0xFF;
            if (b0 == 100 && (b1 & 0xC0) == 64) return true;  // 100.64.0.0/10 CGNAT
            if (b0 == 192 && b1 == 168) return true;           // 192.168.0.0/16（部分平台 siteLocal 未覆盖）
            if (b0 == 169 && b1 == 254) return true;           // 169.254.0.0/16
            if (b0 == 0) return true;                          // 0.0.0.0/8
        } else {
            if (strict && (bytes[0] & 0xFE) == 0xFC) return true;  // fc00::/7 ULA（仅字面 IP 拦）
            if (bytes[0] == 0xFE && (bytes[1] & 0xC0) == 0x80) return true; // fe80::/10 链路本地
        }
        return false;
    }
}
