package com.xmedia.downloader.model;

/** 下载记录状态（数据库 download_record.status）。SKIPPED 不落库，仅计数（S3）。 */
public enum RecordStatus {
    SUCCESS,
    FAILED
}
