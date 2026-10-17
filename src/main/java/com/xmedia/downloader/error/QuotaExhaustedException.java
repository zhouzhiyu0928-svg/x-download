package com.xmedia.downloader.error;

/**
 * 账号每日调用配额耗尽（H2）。
 *
 * <p>与 {@link RateLimitException} 严格区分：配额是「每日调用总量」硬上限，
 * **退避与降并发对配额无效**，只能换号或次日续跑。
 */
public class QuotaExhaustedException extends RuntimeException {

    public QuotaExhaustedException(String message) {
        super(message);
    }
}
