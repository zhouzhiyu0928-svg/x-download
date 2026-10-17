package com.xmedia.downloader.model;

/**
 * 一条下载记录（对应 download_record 表，字段与《数据库结构说明》V1.1 一致）。
 *
 * <p>去重键 = {@code (tweetId, mediaKey)}，且**只有 status=SUCCESS 的记录参与「已下载」判定**（H1）：
 * FAILED 记录允许 UPSERT 覆盖重试，否则失败媒体在增量模式下永不重试。
 */
public record DownloadRecord(
        String tweetId,
        String mediaKey,
        MediaType mediaType,
        String mediaUrl,
        String userRestId,
        String userScreenName,
        TaskType taskType,
        RecordStatus status,
        String localPath,
        String errorMessage,
        long fileSize,
        Integer variantBitrate,
        String tweetCreatedAt,
        String downloadedAt,
        int attemptCount) {

    public static final int DEFAULT_ATTEMPT = 1;
}
