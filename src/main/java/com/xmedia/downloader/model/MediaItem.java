package com.xmedia.downloader.model;

/**
 * 单条媒体。
 *
 * @param tweetId             推文 ID
 * @param mediaKey            媒体稳定标识（X media_key；缺失时由 URL 摘要兜底）—— 去重与命名的稳定键
 * @param userScreenName      所属用户 screen_name（展示用，可变更）
 * @param type                媒体类型
 * @param url                 下载地址（图片为 media_url_https，视频为选定的码率变体地址）
 * @param extension           落盘后缀（不含点），如 jpg / png / mp4
 * @param tweetCreatedAtMillis 推文发布时间（epoch 毫秒）
 * @param variantBitrate      视频码率变体（可空，仅视频有意义）
 */
public record MediaItem(
        String tweetId,
        String mediaKey,
        String userScreenName,
        MediaType type,
        String url,
        String extension,
        long tweetCreatedAtMillis,
        Integer variantBitrate) {

    public MediaItem {
        if (tweetId == null || tweetId.isBlank()) {
            throw new IllegalArgumentException("tweetId 不能为空");
        }
        if (mediaKey == null || mediaKey.isBlank()) {
            throw new IllegalArgumentException("mediaKey 不能为空");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url 不能为空");
        }
    }
}
