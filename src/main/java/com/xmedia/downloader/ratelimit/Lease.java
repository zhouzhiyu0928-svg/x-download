package com.xmedia.downloader.ratelimit;

import com.xmedia.downloader.account.Account;
import com.xmedia.downloader.account.AccountPool;

/**
 * 一次请求的持有凭据：统一归还 RateLimiter 与 AccountPool（S12）。
 *
 * <p>用 try-with-resources 保证所有分支（成功/限流/认证失败/异常）都对称归还，避免信号量泄漏。
 */
public final class Lease implements AutoCloseable {

    private final RateLimiter limiter;
    private final AccountPool pool;
    private final Account account;
    private boolean closed;

    private Lease(RateLimiter limiter, AccountPool pool, Account account) {
        this.limiter = limiter;
        this.pool = pool;
        this.account = account;
    }

    /** 取许可并绑定账号：一次性完成「占并发位 + 绑定账号」，close() 对称归还。 */
    public static Lease open(RateLimiter limiter, AccountPool pool, Account account)
            throws InterruptedException {
        limiter.acquire();
        return new Lease(limiter, pool, account);
    }

    public Account account() {
        return account;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        limiter.release();
        if (pool != null) {
            pool.release(account);
        }
    }
}
