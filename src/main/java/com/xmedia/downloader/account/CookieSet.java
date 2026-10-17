package com.xmedia.downloader.account;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cookie 集合（auth_token + ct0）。
 *
 * <p>安全：{@link #toString()} 只输出「是否已设置」，**绝不打印完整 Cookie**（《详细设计》§7.2）。
 */
public final class CookieSet {

    private final String authToken;
    private final String ct0;
    private final String raw;

    private CookieSet(String authToken, String ct0, String raw) {
        this.authToken = authToken;
        this.ct0 = ct0;
        this.raw = raw;
    }

    public static CookieSet parse(String cookie) {
        if (cookie == null || cookie.isBlank()) {
            throw new IllegalArgumentException("cookie 为空");
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (String part : cookie.split(";")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            int eq = p.indexOf('=');
            if (eq > 0) {
                map.put(p.substring(0, eq).trim(), p.substring(eq + 1).trim());
            }
        }
        String auth = map.get("auth_token");
        String csrf = map.get("ct0");
        if (auth == null || auth.isBlank()) {
            throw new IllegalArgumentException("cookie 缺少 auth_token");
        }
        if (csrf == null || csrf.isBlank()) {
            throw new IllegalArgumentException("cookie 缺少 ct0");
        }
        return new CookieSet(auth, csrf, cookie);
    }

    public String authToken() {
        return authToken;
    }

    /** X 的 csrf 头与 ct0 同值。 */
    public String csrfToken() {
        return ct0;
    }

    /** 原始 cookie 串（仅供构造请求头，禁止进日志）。 */
    public String rawForHeader() {
        return raw;
    }

    @Override
    public String toString() {
        return "CookieSet(auth_token=***, ct0=***)";
    }
}
