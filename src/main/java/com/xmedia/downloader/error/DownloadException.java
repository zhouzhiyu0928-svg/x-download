package com.xmedia.downloader.error;

/** 单文件下载失败（记录后继续其它文件）。 */
public class DownloadException extends RuntimeException {

    public DownloadException(String message) {
        super(message);
    }

    public DownloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
