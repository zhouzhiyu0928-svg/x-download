package com.xmedia.downloader.config;

/** 配置缺失 / 格式错误（启动阶段直接失败并给出可读提示，需求 F19）。 */
public class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
