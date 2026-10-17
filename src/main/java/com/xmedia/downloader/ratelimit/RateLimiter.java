package com.xmedia.downloader.ratelimit;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 频率限流：并发信号量 + 最小请求间隔 + 全局退避（H2 的「频率」一侧）。
 *
 * <p>注意：本类**只管频率**。每日调用总量由 {@link QuotaLedger} 管；配额耗尽时退避无效。
 */
public final class RateLimiter implements AutoCloseable {

    private final Semaphore permits;
    private final long minIntervalMillis;
    private final AtomicLong nextAllowedAt = new AtomicLong();

    public RateLimiter(int maxConcurrent, long minIntervalMillis) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("并发数必须 ≥ 1");
        }
        this.permits = new Semaphore(maxConcurrent, true);
        this.minIntervalMillis = Math.max(0, minIntervalMillis);
    }

    /** 获取一次请求许可：先占并发位，再等到距上次请求满最小间隔。 */
    public void acquire() throws InterruptedException {
        permits.acquire();
        while (true) {
            long now = System.currentTimeMillis();
            long next = nextAllowedAt.get();
            if (now >= next) {
                if (nextAllowedAt.compareAndSet(next, now + minIntervalMillis)) {
                    return;
                }
                continue;
            }
            long wait = next - now;
            if (wait > 0) {
                Thread.sleep(wait);
            }
        }
    }

    public void release() {
        permits.release();
    }

    /** 收到限流响应后全局退避（只往后推，不会缩短已有的等待）。 */
    public void onRateLimited(Duration backoff) {
        long until = System.currentTimeMillis() + backoff.toMillis();
        nextAllowedAt.accumulateAndGet(until, Math::max);
    }

    public int availablePermits() {
        return permits.availablePermits();
    }

    @Override
    public void close() {
        // 无需释放资源；保留 AutoCloseable 以便 try-with-resources 统一管理
    }
}
