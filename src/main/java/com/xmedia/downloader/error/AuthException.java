package com.xmedia.downloader.error;

/** 认证失败（Cookie 失效 / 401 / 403）。只记账号 name 与错误类型，绝不打印完整 Cookie。 */
public class AuthException extends RuntimeException {

    private final String accountName;

    public AuthException(String accountName, String message) {
        super(message);
        this.accountName = accountName;
    }

    public String accountName() {
        return accountName;
    }
}
