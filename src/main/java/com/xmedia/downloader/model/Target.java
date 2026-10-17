package com.xmedia.downloader.model;

/**
 * ID 列表条目（用户要求的「可增加/可删除/可校验的 ID 清单」）。
 *
 * @param sortOrder   列表顺序（下载按此顺序逐个执行）
 * @param enabled     是否参与批量下载（停用=保留但不下载）
 * @param lastError   最近一次校验的错误原因（INVALID 时给界面提示）
 */
public record Target(
        long id,
        String screenName,
        String note,
        TargetStatus status,
        String restId,
        String displayName,
        int mediaCount,
        int tweetCount,
        String lastError,
        String lastCheckedAt,
        boolean enabled,
        int sortOrder,
        String createdAt) {

    /** 展示用：@用户名（昵称）。 */
    public String label() {
        if (displayName == null || displayName.isBlank() || displayName.equals(screenName)) {
            return "@" + screenName;
        }
        return "@" + screenName + "（" + displayName + "）";
    }

    public boolean usable() {
        return enabled && status != TargetStatus.INVALID;
    }
}
