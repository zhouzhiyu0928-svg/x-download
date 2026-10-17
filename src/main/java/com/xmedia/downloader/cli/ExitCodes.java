package com.xmedia.downloader.cli;

/** 进程退出码（固定，便于脚本判断）。 */
public final class ExitCodes {

    public static final int OK = 0;
    public static final int GENERAL = 1;
    /** 参数用法错误（picocli 默认也是 2）。 */
    public static final int USAGE = 2;
    public static final int CONFIG = 3;
    public static final int AUTH = 4;
    public static final int QUOTA = 5;

    private ExitCodes() {
    }
}
