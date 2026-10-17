package com.xmedia.downloader.error;

/** 频率限流（429 / Rate Limit）——可通过退避或换号恢复。 */
public class RateLimitException extends RuntimeException {

    public RateLimitException(String message) {
        super(message);
    }
}
