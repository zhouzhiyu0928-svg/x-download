package com.xmedia.downloader.account;

import com.xmedia.downloader.config.AppConfig;
import com.xmedia.downloader.error.AuthException;
import com.xmedia.downloader.error.QuotaExhaustedException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 多账号 Cookie 池（H4 第一阶段纳入 / H6 指定账号绑定）。
 *
 * <p>{@link #acquire()} = AUTO：优先「可用且最近未使用」；{@link #acquire(String)} = 指定账号，
 * 用于 likes / 亮点 等「本人账号」语义的任务，**不得轮换**。
 */
public final class AccountPool {

    private final Map<String, Account> accounts = new LinkedHashMap<>();

    public AccountPool(List<Account> accounts) {
        for (Account a : accounts) {
            if (this.accounts.put(a.name(), a) != null) {
                throw new IllegalArgumentException("账号名重复：" + a.name());
            }
        }
        if (this.accounts.isEmpty()) {
            throw new IllegalArgumentException("账号池不能为空");
        }
    }

    public static AccountPool from(AppConfig config, Instant now) {
        List<Account> list = new ArrayList<>();
        for (AppConfig.AccountConfig ac : config.accounts()) {
            list.add(new Account(ac.name(), ac.note(), CookieSet.parse(ac.cookie()),
                    config.quota().perAccountDaily()));
        }
        return new AccountPool(list);
    }

    public List<Account> accounts() {
        return List.copyOf(accounts.values());
    }

    /** AUTO 轮换：优先可用且最近未使用；全部不可用则抛异常（由调用方决定停机或次日续跑）。 */
    public synchronized Account acquire(Instant now) {
        Optional<Account> pick = accounts.values().stream()
                .filter(a -> a.usable(now))
                .min(Comparator.comparing(Account::lastUsedAt));
        if (pick.isEmpty()) {
            throw new QuotaExhaustedException("没有可用账号（要么全部限流/失效，要么当日配额已耗尽）；"
                    + "配额耗尽需次日续跑或追加账号");
        }
        Account a = pick.get();
        a.touch(now);
        return a;
    }

    public Account acquire() {
        return acquire(Instant.now());
    }

    /** 指定账号：Likes / 亮点 必须使用 cookie 所属账号本人（H6）。 */
    public synchronized Account acquire(String preferredName, Instant now) {
        Account a = accounts.get(preferredName);
        if (a == null) {
            throw new AuthException(preferredName, "指定账号不存在：" + preferredName);
        }
        if (a.status() == AccountStatus.INVALID) {
            throw new AuthException(preferredName, "指定账号 Cookie 已失效：" + preferredName);
        }
        if (a.remainingQuota() <= 0) {
            throw new QuotaExhaustedException("指定账号 " + preferredName + " 当日配额已耗尽（该任务不可换号）");
        }
        a.touch(now);
        return a;
    }

    /** 指定账号优先：有则用指定，无则 AUTO（供 accountBinding=AUTO 的普通任务）。 */
    public synchronized Account acquirePreferring(String preferredName, Instant now) {
        if (preferredName == null || preferredName.isBlank()) {
            return acquire(now);
        }
        if (accounts.containsKey(preferredName)) {
            return acquire(preferredName, now);
        }
        return acquire(now);
    }

    public synchronized void release(Account account) {
        // 归还即无持有状态（本实现不需要引用计数）；保留方法以对称释放语义（S12）
    }

    public synchronized void markRateLimited(Account account, Duration cooldown, Instant now) {
        account.markRateLimited(cooldown, now);
    }

    public synchronized void markInvalid(Account account) {
        account.markInvalid();
    }

    public synchronized void markQuotaExhausted(Account account) {
        account.markQuotaExhausted();
    }

    /** 全部账号剩余配额合计（启动前估算用）；若有账号不限次数则直接返回不限，避免求和溢出。 */
    public synchronized int totalRemainingQuota() {
        int sum = 0;
        for (Account a : accounts.values()) {
            if (a.unlimitedQuota()) {
                return Integer.MAX_VALUE;
            }
            sum += a.remainingQuota();
        }
        return sum;
    }

    public synchronized long usableCount(Instant now) {
        return accounts.values().stream().filter(a -> a.usable(now)).count();
    }

    public synchronized void syncQuota(String accountName, int usedToday, int limit) {
        Account a = accounts.get(accountName);
        if (a != null) {
            a.syncQuota(usedToday, limit);
        }
    }
}
