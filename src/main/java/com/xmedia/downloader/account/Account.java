package com.xmedia.downloader.account;

import java.time.Duration;
import java.time.Instant;

/**
 * 一个账号：Cookie + 健康状态 + 当日配额。
 *
 * <p>配额按「账号 × 自然日」计（H2）；退避只影响 {@link AccountStatus#RATE_LIMITED}，
 * 与 {@link AccountStatus#QUOTA_EXHAUSTED} 是两回事 —— 后者今天不可再用。
 */
public final class Account {

    private final String name;
    private final String note;
    private final CookieSet cookie;

    private volatile AccountStatus status = AccountStatus.AVAILABLE;
    private volatile Instant cooldownUntil = Instant.EPOCH;
    private volatile Instant lastUsedAt = Instant.EPOCH;
    private volatile int quotaUsedToday;
    private volatile int quotaLimit;

    public Account(String name, String note, CookieSet cookie, int quotaLimit) {
        this.name = name;
        this.note = note == null ? "" : note;
        this.cookie = cookie;
        this.quotaLimit = quotaLimit;
    }

    public String name() {
        return name;
    }

    public String note() {
        return note;
    }

    public CookieSet cookie() {
        return cookie;
    }

    public boolean selfAccount() {
        return !note.isBlank();
    }

    public AccountStatus status() {
        return status;
    }

    public Instant lastUsedAt() {
        return lastUsedAt;
    }

    public Instant cooldownUntil() {
        return cooldownUntil;
    }

    public int quotaLimit() {
        return quotaLimit;
    }

    public int quotaUsedToday() {
        return quotaUsedToday;
    }

    /** 每日调用上限；{@code <= 0} 表示**不限制**（用户可选择取消次数上限）。 */
    public boolean unlimitedQuota() {
        return quotaLimit <= 0;
    }

    public int remainingQuota() {
        if (unlimitedQuota()) {
            return Integer.MAX_VALUE;   // 不限
        }
        return Math.max(0, quotaLimit - quotaUsedToday);
    }

    /** 是否可用于新请求：状态可用、冷却已过、配额未耗尽。 */
    public boolean usable(Instant now) {
        if (status == AccountStatus.INVALID || status == AccountStatus.QUOTA_EXHAUSTED) {
            return false;
        }
        if (remainingQuota() <= 0) {
            return false;
        }
        return !now.isBefore(cooldownUntil);
    }

    public void touch(Instant now) {
        this.lastUsedAt = now;
        if (status == AccountStatus.RATE_LIMITED && !now.isBefore(cooldownUntil)) {
            this.status = AccountStatus.AVAILABLE;
        }
    }

    public void markRateLimited(Duration cooldown, Instant now) {
        this.status = AccountStatus.RATE_LIMITED;
        this.cooldownUntil = now.plus(cooldown);
    }

    public void markInvalid() {
        this.status = AccountStatus.INVALID;
    }

    public void markQuotaExhausted() {
        if (!unlimitedQuota()) {
            this.quotaUsedToday = this.quotaLimit;
        }
        this.status = AccountStatus.QUOTA_EXHAUSTED;
    }

    /** 记录一次调用；达到上限即置为配额耗尽（不限时永不置为耗尽）。 */
    public void recordCall(Instant now) {
        recordCalls(now, 1);
    }

    /**
     * 一次记多次调用（配额账本批量补记时用）。
     *
     * <p>必须按次数累加：早先只 +1，导致账号自身计数与账本/界面显示漂移。
     */
    public void recordCalls(Instant now, int calls) {
        if (calls <= 0) {
            return;
        }
        this.quotaUsedToday += calls;
        this.lastUsedAt = now;
        if (!unlimitedQuota() && this.quotaUsedToday >= this.quotaLimit) {
            this.status = AccountStatus.QUOTA_EXHAUSTED;
        }
    }

    public void syncQuota(int usedToday, int limit) {
        this.quotaUsedToday = usedToday;
        this.quotaLimit = limit;
        if (limit > 0 && usedToday >= limit) {
            this.status = AccountStatus.QUOTA_EXHAUSTED;
        } else if (this.status == AccountStatus.QUOTA_EXHAUSTED) {
            this.status = AccountStatus.AVAILABLE;
        }
    }

    @Override
    public String toString() {
        return "Account(" + name + ", status=" + status + ", quota=" + quotaUsedToday + "/"
                + (quotaLimit <= 0 ? "不限" : quotaLimit)
                + ", " + cookie + ")";
    }
}
