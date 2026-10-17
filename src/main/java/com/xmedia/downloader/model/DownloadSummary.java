package com.xmedia.downloader.model;

import java.util.List;

/** 一次下载批次的汇总。 */
public record DownloadSummary(int successCount, int failedCount, int skippedCount, long downloadedBytes,
                              List<String> failedItems) {

    public DownloadSummary {
        failedItems = failedItems == null ? List.of() : List.copyOf(failedItems);
    }

    public int total() {
        return successCount + failedCount + skippedCount;
    }
}
