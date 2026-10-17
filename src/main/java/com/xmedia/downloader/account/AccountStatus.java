package com.xmedia.downloader.account;

/** 账号状态。{@link #QUOTA_EXHAUSTED} 与 {@link #RATE_LIMITED} 语义不同：前者今日不可再用（H2）。 */
public enum AccountStatus {
    AVAILABLE,
    RATE_LIMITED,
    INVALID,
    QUOTA_EXHAUSTED
}
