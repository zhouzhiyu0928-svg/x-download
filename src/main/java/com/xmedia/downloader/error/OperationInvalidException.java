package com.xmedia.downloader.error;

/**
 * GraphQL 操作失效（queryId 过期 / 操作被下线）—— S13 要单独识别，不能当成普通网络失败重试。
 *
 * <p>识别信号：HTTP 400 且响应含 {@code Query} 相关错误，或响应 errors 里出现
 * {@code Unknown operation} / {@code Query: Unspecified}；也可能表现为响应 JSON 里出现
 * {@code "operationName"} 校验失败。此时应提示用户更新 operations.yaml。
 */
public class OperationInvalidException extends RuntimeException {

    private final String operationName;

    public OperationInvalidException(String operationName, String message) {
        super(message);
        this.operationName = operationName;
    }

    public String operationName() {
        return operationName;
    }
}
