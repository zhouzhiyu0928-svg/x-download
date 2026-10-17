package com.xmedia.downloader.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xmedia.downloader.account.Account;
import com.xmedia.downloader.account.AccountPool;
import com.xmedia.downloader.config.AppConfig;
import com.xmedia.downloader.storage.Database;
import com.xmedia.downloader.storage.QuotaLedgerRepository;
import com.xmedia.downloader.support.TestConfigs;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 「取消调用次数限制」：{@code quota.per-account-daily = 0} 时不再有每日上限，
 * 账号永远不会因为次数被置为耗尽，启动前估算也不拒绝。
 */
class QuotaUnlimitedTest {

    @TempDir
    Path dir;

    private Database db;
    private AccountPool pool;

    @BeforeEach
    void setUp() {
        db = Database.open(dir.resolve("q.db").toString());
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private QuotaLedger ledger(int perAccountDaily) {
        AppConfig config = TestConfigs.withQuota(
                TestConfigs.minimal(dir.resolve("cfg.db").toString(), dir.resolve("d").toString(), 2, 0, 1),
                perAccountDaily, 0);
        pool = AccountPool.from(config, Instant.now());
        QuotaLedger l = new QuotaLedger(new QuotaLedgerRepository(db), config.quota(),
                Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC));
        l.loadInto(pool);
        return l;
    }

    @Test
    @DisplayName("配额 0 = 不限：不设上限、不判耗尽、估算永远够")
    void unlimited() {
        QuotaLedger l = ledger(0);
        assertTrue(l.unlimited());
        Account account = pool.acquire();

        l.recordCalls(account, 5000);
        assertEquals(5000, l.usedToday(account), "用量照常统计（供界面展示）");
        assertEquals(Integer.MAX_VALUE, l.remaining(account));
        assertTrue(account.usable(Instant.now()), "不限次数时账号不会因次数被停用");
        assertTrue(account.unlimitedQuota(), "账号应处于不限状态");

        QuotaLedger.Estimate estimate = l.estimate(100000, pool);
        assertTrue(estimate.sufficient());
        assertEquals(1, estimate.accountsNeeded());
        l.assertCanRun(estimate);   // 不抛异常
    }

    @Test
    @DisplayName("配置上限时行为不变：到达上限即耗尽（回归保护）")
    void limitedStillWorks() {
        QuotaLedger l = ledger(3);
        assertFalse(l.unlimited());
        Account account = pool.acquire();

        l.recordCalls(account, 2);
        assertEquals(1, l.remaining(account));
        assertTrue(account.usable(Instant.now()));

        l.recordCalls(account, 1);
        assertEquals(0, l.remaining(account));
        assertFalse(account.usable(Instant.now()), "达到上限后不再可用");
    }
}
