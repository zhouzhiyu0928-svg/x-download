package com.xmedia.downloader.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xmedia.downloader.account.Account;
import com.xmedia.downloader.account.AccountPool;
import com.xmedia.downloader.account.CookieSet;
import com.xmedia.downloader.config.AppConfig;
import com.xmedia.downloader.error.QuotaExhaustedException;
import com.xmedia.downloader.storage.Database;
import com.xmedia.downloader.storage.QuotaLedgerRepository;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 频率限流（信号量/间隔/退避/Lease 对称归还）与每日配额账本（H2/S12）。 */
class RateLimiterQuotaTest {

    private static final String COOKIE = "auth_token=t; ct0=c;";

    @Test
    @DisplayName("并发信号量：acquire 占用、release 归还；Lease 关闭即归还")
    void semaphoreAndLease() throws Exception {
        RateLimiter limiter = new RateLimiter(2, 0);
        limiter.acquire();
        limiter.acquire();
        assertEquals(0, limiter.availablePermits());

        limiter.release();
        assertEquals(1, limiter.availablePermits());

        AccountPool pool = new AccountPool(List.of(new Account("a1", "", CookieSet.parse(COOKIE), 100)));
        Account a = pool.acquire(Instant.now());
        try (Lease lease = Lease.open(limiter, pool, a)) {   // open 内部即占许可
            assertEquals("a1", lease.account().name());
            assertEquals(0, limiter.availablePermits());
        }
        assertEquals(1, limiter.availablePermits(), "Lease.close 应归还许可");

        // 二次 close 幂等，不得重复归还
        Lease lease = Lease.open(limiter, pool, a);
        lease.close();
        lease.close();
        assertEquals(1, limiter.availablePermits());
    }

    @Test
    @DisplayName("最小请求间隔被强制执行")
    void minIntervalEnforced() throws Exception {
        RateLimiter limiter = new RateLimiter(4, 60);
        long start = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            limiter.acquire();
            limiter.release();
            // 归还许可但间隔仍须生效：串行 acquire 之间至少 60ms
        }
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed >= 110, "3 次请求至少应跨越 2 个 60ms 间隔，实际 " + elapsed + "ms");
    }

    @Test
    @DisplayName("限流退避：onRateLimited 之后的下一次请求被推迟")
    void backoffDelaysNextRequest() throws Exception {
        RateLimiter limiter = new RateLimiter(1, 0);
        limiter.onRateLimited(Duration.ofMillis(150));
        long start = System.currentTimeMillis();
        limiter.acquire();
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed >= 120, "退避应至少推迟 150ms，实际 " + elapsed + "ms");
    }

    @Test
    @DisplayName("H2：配额账本累计 + 估算 + 不足时拒绝启动（并提示所需账号数）")
    void quotaEstimateAndGuard(@TempDir Path dir) {
        try (Database db = Database.open(dir.resolve("q.db").toString())) {
            QuotaLedgerRepository repo = new QuotaLedgerRepository(db);
            Clock clock = Clock.fixed(Instant.parse("2026-10-12T08:00:00Z"), ZoneId.of("Asia/Shanghai"));
            AppConfig.QuotaConfig cfg = new AppConfig.QuotaConfig(100, 10, 19);
            QuotaLedger ledger = new QuotaLedger(repo, cfg, clock);

            AccountPool pool = new AccountPool(List.of(
                    new Account("a1", "", CookieSet.parse(COOKIE), 100),
                    new Account("a2", "", CookieSet.parse(COOKIE), 100)));
            ledger.loadInto(pool);

            // 估算：100 条推文 ÷ 19 条/次 ⇒ 6 次调用；可用 200 次
            QuotaLedger.Estimate est = ledger.estimate(100, pool);
            assertEquals(6, est.callsNeeded());
            assertEquals(200, est.quotaAvailable());
            assertTrue(est.sufficient());
            assertTrue(ledger.estimate(50, pool).sufficient());

            // 记录调用（落库 + 内存）
            Account a1 = pool.acquire("a1", clock.instant());
            ledger.recordCall(a1);
            ledger.recordCall(a1);
            db.writer().flush();
            assertEquals(2, ledger.usedToday(a1));
            assertEquals(98, ledger.remaining(a1));
            assertEquals(2, repo.usedToday("a1", ledger.today()), "应已落库（跨进程可续）");

            // 大规模需求 → 配额不足，异常须提示「至少需要几个账号」
            QuotaLedger.Estimate huge = ledger.estimate(100_000, pool);
            assertEquals(5264, huge.callsNeeded());
            assertTrue(!huge.sufficient());
            QuotaExhaustedException e = assertThrows(QuotaExhaustedException.class,
                    () -> ledger.assertCanRun(huge));
            assertTrue(e.getMessage().contains("至少需要"), e.getMessage());
            assertTrue(e.getMessage().contains("配额不足"), e.getMessage());
        }
    }

    @Test
    @DisplayName("重启后账本可续：库里的用量会同步回账号对象")
    void ledgerSurvivesRestart(@TempDir Path dir) {
        String path = dir.resolve("restart.db").toString();
        Clock clock = Clock.fixed(Instant.parse("2026-10-12T08:00:00Z"), ZoneId.of("Asia/Shanghai"));
        AppConfig.QuotaConfig cfg = new AppConfig.QuotaConfig(10, 1, 19);

        try (Database db = Database.open(path)) {
            QuotaLedger ledger = new QuotaLedger(new QuotaLedgerRepository(db), cfg, clock);
            AccountPool pool = new AccountPool(List.of(new Account("a1", "", CookieSet.parse(COOKIE), 10)));
            ledger.loadInto(pool);
            ledger.recordCalls(pool.acquire("a1", clock.instant()), 9);
            db.writer().flush();
        }

        try (Database db = Database.open(path)) {
            QuotaLedger ledger = new QuotaLedger(new QuotaLedgerRepository(db), cfg, clock);
            AccountPool pool = new AccountPool(List.of(new Account("a1", "", CookieSet.parse(COOKIE), 10)));
            ledger.loadInto(pool);
            assertEquals(9, ledger.usedToday(pool.accounts().get(0)));
            assertEquals(1, ledger.remaining(pool.accounts().get(0)));
            assertEquals(1, pool.totalRemainingQuota());
            ledger.recordCall(pool.acquire("a1", clock.instant()));
            assertEquals(0, pool.totalRemainingQuota(), "用满后应不可用");
            assertThrows(QuotaExhaustedException.class, () -> pool.acquire(clock.instant()));
            assertEquals(LocalDate.of(2026, 10, 12), ledger.today());
        }
    }
}
