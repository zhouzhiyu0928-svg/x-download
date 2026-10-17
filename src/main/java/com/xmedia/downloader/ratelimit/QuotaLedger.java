package com.xmedia.downloader.ratelimit;

import com.xmedia.downloader.account.Account;
import com.xmedia.downloader.account.AccountPool;
import com.xmedia.downloader.config.AppConfig;
import com.xmedia.downloader.error.QuotaExhaustedException;
import com.xmedia.downloader.storage.QuotaLedgerRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 账号每日调用配额账本（H2）。
 *
 * <p>与「频率限流」严格分开：配额是每日调用**总量**硬上限，耗尽后只能换号或次日续跑。
 * 账本落库（{@code quota_ledger}）→ 跨进程可续；启动前用
 * {@link #estimate(long, AccountPool)} 估算所需调用数并在不足时拒绝启动。
 */
public final class QuotaLedger {

    private final QuotaLedgerRepository repository;
    private final AppConfig.QuotaConfig config;
    private final Clock clock;
    private final Map<String, Integer> inMemoryUsed = new LinkedHashMap<>();

    public QuotaLedger(QuotaLedgerRepository repository, AppConfig.QuotaConfig config) {
        this(repository, config, Clock.systemDefaultZone());
    }

    public QuotaLedger(QuotaLedgerRepository repository, AppConfig.QuotaConfig config, Clock clock) {
        this.repository = repository;
        this.config = config;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** 把库里的当日用量同步进内存账号对象（启动时调用）。 */
    public void loadInto(AccountPool pool) {
        for (Account a : pool.accounts()) {
            int used = repository.usedToday(a.name(), today());
            inMemoryUsed.put(a.name(), used);
            pool.syncQuota(a.name(), used, config.perAccountDaily());
        }
    }

    public int usedToday(Account account) {
        return inMemoryUsed.computeIfAbsent(account.name(),
                n -> repository.usedToday(n, today()));
    }

    /** 是否不限次数（{@code quota.per-account-daily <= 0}，用户的「取消调用次数限制」）。 */
    public boolean unlimited() {
        return config.perAccountDaily() <= 0;
    }

    public int remaining(Account account) {
        if (unlimited()) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, config.perAccountDaily() - usedToday(account));
    }

    /** 记一次调用：内存 + 落库（跨进程可续）。 */
    public void recordCall(Account account) {
        recordCalls(account, 1);
    }

    public void recordCalls(Account account, int calls) {
        if (calls <= 0) {
            return;
        }
        inMemoryUsed.merge(account.name(), calls, Integer::sum);
        account.recordCalls(java.time.Instant.now(clock), calls);
        repository.addCalls(account.name(), today(), calls, config.perAccountDaily());
    }

    /** 启动前估算：所需调用数 = ceil(推文数 / 每调用可覆盖推文数)。 */
    public Estimate estimate(long tweetsToFetch, AccountPool pool) {
        int perCall = Math.max(1, config.estimateTweetsPerCall());
        int needed = (int) Math.ceil((double) Math.max(0, tweetsToFetch) / perCall);
        int available = unlimited() ? Integer.MAX_VALUE : pool.totalRemainingQuota();
        return new Estimate(tweetsToFetch, needed, available,
                pool.usableCount(java.time.Instant.now(clock)), config.perAccountDaily());
    }

    /** 估算不足则拒绝启动，并明确「需要几个账号」；不限次数时永不拒绝。 */
    public void assertCanRun(Estimate estimate) {
        if (unlimited()) {
            return;
        }
        if (!estimate.sufficient()) {
            throw new QuotaExhaustedException(String.format(
                    "调用配额不足：预计需要 %d 次调用（推文 %d 条 ÷ %d 条/次），当前可用 %d 次；"
                            + "按每账号每日 %d 次估算，至少需要 %d 个可用账号",
                    estimate.callsNeeded(), estimate.tweets(), config.estimateTweetsPerCall(),
                    estimate.quotaAvailable(), config.perAccountDaily(), estimate.accountsNeeded()));
        }
    }

    public record Estimate(long tweets, int callsNeeded, int quotaAvailable, long usableAccounts,
                           int perAccountDaily) {

        public boolean sufficient() {
            return perAccountDaily <= 0 || quotaAvailable >= callsNeeded;
        }

        public int accountsNeeded() {
            if (perAccountDaily <= 0) {
                return 1;   // 不限次数：一个账号就够
            }
            return (int) Math.ceil((double) callsNeeded / perAccountDaily);
        }
    }
}
