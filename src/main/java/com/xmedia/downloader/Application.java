package com.xmedia.downloader;

import com.xmedia.downloader.cli.MainCommand;
import picocli.CommandLine;

/** 程序入口。 */
public final class Application {

    public static final String VERSION = "0.1.0-SNAPSHOT";

    private Application() {
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new MainCommand()).execute(args);
        System.exit(exitCode);
    }
}
