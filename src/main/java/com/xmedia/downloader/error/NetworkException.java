package com.xmedia.downloader.error;

/** 网络 / 代理问题，可重试。 */
public class NetworkException extends RuntimeException {

    public NetworkException(String message, Throwable cause) {
        super(message, cause);
    }
}
