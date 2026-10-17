package com.xmedia.downloader.model;

/**
 * 用户主页信息（F10）。
 *
 * @param avatarUrl  头像地址（已从 _normal 升级为 _400x400）
 * @param bannerUrl  banner 地址，未设置时为 null
 */
public record UserProfile(String restId, String screenName, String name, String description,
                          String avatarUrl, String bannerUrl, int statusesCount, int mediaCount) {
}
