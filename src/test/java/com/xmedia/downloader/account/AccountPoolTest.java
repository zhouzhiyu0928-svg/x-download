package com.xmedia.downloader.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xmedia.downloader.error.QuotaExhaustedException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 账号池：AUTO 轮换、指定账号绑定（H6）、配额与限流状态、Cookie 不泄漏。 */
class AccountPoolTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");
    private static final String OK_COOKIE = "auth_token=SECRET_TOKEN_VALUE; ct0=SECRET_CT0_VALUE;";

    private static Account account(String name, String note, int quota) {
        return new Account(name, note, CookieSet.parse(OK_COOKIE), quota);
    }

    @Test
    @DisplayName("CookieSet：解析成功；toString 绝不泄漏 token")
    void cookieParsingAndRedaction() {
        CookieSet c = CookieSet.parse(OK_COOKIE);
        assertEquals("SECRET_TOKEN_VALUE", c.authToken());
        assertEquals("SECRET_CT0_VALUE", c.csrfToken());
        assertFalse(c.toString().contains("SECRET"), "toString 不得包含 Cookie 明文：" + c);
        assertTrue(c.toString().contains("***"));
        assertThrows(IllegalArgumentException.class, () -> CookieSet.parse("auth_token=only;"));
        assertThrows(IllegalArgumentException.class, () -> CookieSet.parse(""));
    }

    @Test
    @DisplayName("AUTO：优先可用且最近未使用；用完的账号不再被选中")
    void autoRotationPicksLeastRecentlyUsed() {
        AccountPool pool = new AccountPool(List.of(account("a1", "", 100), account("a2", "", 100)));

        Account first = pool.acquire(NOW);
        Account second = pool.acquire(NOW.plusSeconds(1));
        assertFalse(first.name().equals(second.name()), "应轮换到另一个账号");

        pool.markRateLimited(second, Duration.ofMinutes(10), NOW.plusSeconds(2));
        assertFalse(second.usable(NOW.plusSeconds(3)), "限流中的账号不可用");
        Account third = pool.acquire(NOW.plusSeconds(3));
        assertEquals(first.name().equals(third.name()) ? first.name() : third.name(), third.name());
        assertTrue(third.usable(NOW.plusSeconds(3)));
    }

    @Test
    @DisplayName("H6：指定账号必须被使用，且不因限流而轮换")
    void specifiedAccountIsBound() {
        AccountPool pool = new AccountPool(List.of(account("self", "本人账号", 100), account("other", "", 100)));

        Account bound = pool.acquire("self", NOW);
        assertEquals("self", bound.name());
        assertTrue(bound.selfAccount());

        pool.markRateLimited(bound, Duration.ofHours(1), NOW);
        // 即使该账号限流，指定绑定也不得换号（Likes 语义不允许）
        Account stillBound = pool.acquire("self", NOW.plusSeconds(1));
        assertEquals("self", stillBound.name(), "指定账号不得被轮换掉");

        assertThrows(RuntimeException.class, () -> pool.acquire("nope", NOW));
    }

    @Test
    @DisplayName("H2：配额耗尽 → 不可用；指定账号配额耗尽 → 明确异常（不可换号）")
    void quotaExhaustion() {
        AccountPool pool = new AccountPool(List.of(account("a1", "", 2)));
        Account a = pool.acquire(NOW);

        a.recordCall(NOW);
        assertEquals(1, a.remainingQuota());
        assertTrue(a.usable(NOW));

        a.recordCall(NOW);
        assertEquals(0, a.remainingQuota());
        assertEquals(AccountStatus.QUOTA_EXHAUSTED, a.status());
        assertFalse(a.usable(NOW));

        QuotaExhaustedException e = assertThrows(QuotaExhaustedException.class, () -> pool.acquire(NOW));
        assertTrue(e.getMessage().contains("配额") || e.getMessage().contains("账号"), e.getMessage());
        assertThrows(QuotaExhaustedException.class, () -> pool.acquire("a1", NOW));
    }

    @Test
    @DisplayName("失效账号被跳过；全部失效时报错")
    void invalidAccountsSkipped() {
        AccountPool pool = new AccountPool(List.of(account("a1", "", 100), account("a2", "", 100)));
        Account a1 = pool.acquire("a1", NOW);
        pool.markInvalid(a1);
        assertEquals(AccountStatus.INVALID, a1.status());

        Account next = pool.acquire(NOW);
        assertEquals("a2", next.name());

        pool.markInvalid(next);
        assertThrows(QuotaExhaustedException.class, () -> pool.acquire(NOW));
    }

    @Test
    @DisplayName("剩余配额合计与可用账号数（启动前估算用）")
    void totals() {
        AccountPool pool = new AccountPool(List.of(account("a1", "", 10), account("a2", "", 20)));
        assertEquals(30, pool.totalRemainingQuota());
        assertEquals(2, pool.usableCount(NOW));
        pool.syncQuota("a1", 10, 10);
        assertEquals(20, pool.totalRemainingQuota());
        assertEquals(1, pool.usableCount(NOW));
    }
}
