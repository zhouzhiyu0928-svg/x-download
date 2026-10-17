package com.xmedia.downloader.model;

import java.util.List;

/**
 * 推文。
 *
 * @param mediaItems 媒体列表（去重键为 tweetId + mediaKey）
 */
public record Tweet(
        String tweetId,
        String text,
        long createdAtMillis,
        String userRestId,
        String userScreenName,
        boolean retweet,
        List<MediaItem> mediaItems) {

    public Tweet {
        mediaItems = mediaItems == null ? List.of() : List.copyOf(mediaItems);
    }
}
