package com.xmedia.downloader.error;

/**
 * 目标账号不存在 / 不可用（ID 无效）。
 *
 * <p>与网络错误、限流严格区分：**只有**拿到「这个用户不存在」这类确定性信号才抛本异常，
 * 以便 ID 列表把该条目标标记为 INVALID 并提醒用户删除；网络抖动不能把好 ID 判成无效。
 */
public class UserNotFoundException extends RuntimeException {

    private final String screenName;

    public UserNotFoundException(String screenName, String message) {
        super(message);
        this.screenName = screenName;
    }

    public String screenName() {
        return screenName;
    }
}
