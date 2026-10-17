package com.xmedia.downloader.model;

import java.util.List;

/**
 * 一次任务执行结果。
 *
 * @param resumeCursor 配额耗尽等原因停机时的续跑点（水位线），可空
 */
public record TaskResult(
        String taskName,
        Status status,
        int successCount,
        int failedCount,
        int skippedCount,
        long elapsedMillis,
        List<String> failedItems,
        String resumeCursor,
        int apiCallCount) {

    public enum Status {
        SUCCESS,
        PARTIAL,
        FAILED,
        ABORTED
    }

    public TaskResult {
        failedItems = failedItems == null ? List.of() : List.copyOf(failedItems);
    }
}
