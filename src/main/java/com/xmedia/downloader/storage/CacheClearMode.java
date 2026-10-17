package com.xmedia.downloader.storage;

/** 缓存清理三档（S8）：只删记录 / 记录+文件 / 记录+文件+空目录。 */
public enum CacheClearMode {
    RECORDS_ONLY,
    RECORDS_AND_FILES,
    RECORDS_FILES_AND_DIRS
}
