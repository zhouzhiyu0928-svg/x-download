package com.xmedia.downloader.cli;

import com.xmedia.downloader.config.AppConfig;
import com.xmedia.downloader.config.ConfigException;
import com.xmedia.downloader.console.ConsoleServer;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/** {@code console} 子命令：启动本地图形操作台（F23）。 */
@Command(name = "console", mixinStandardHelpOptions = true,
        description = "启动本地图形操作台（浏览器访问 http://127.0.0.1:<port>），Ctrl+C 退出")
public final class ConsoleCommand implements Callable<Integer> {

    @ParentCommand
    MainCommand parent;

    @Option(names = {"-p", "--port"}, defaultValue = "8848", description = "监听端口（默认 ${DEFAULT-VALUE}）")
    int port;

    @Option(names = "--no-browser", description = "不自动打开浏览器")
    boolean noBrowser;

    @Override
    public Integer call() {
        AppConfig config;
        try {
            config = parent.loadConfig();
        } catch (ConfigException e) {
            System.err.println("[配置错误] " + e.getMessage());
            return ExitCodes.CONFIG;
        }
        ConsoleServer server = new ConsoleServer(config, parent.operationsOverride());
        server.setDryRunForced(parent.dryRun);
        try {
            int bound = server.start(port);
            String url = "http://127.0.0.1:" + bound + "/";
            System.out.println("操作台已启动：" + url);
            System.out.println("  配置：save-path=" + config.savePath() + "  db=" + config.dbPath());
            System.out.println("  账号： " + config.accounts().size() + " 个；代理："
                    + (config.proxy().enabled() ? config.proxy().firstOrNull() : "未启用"));
            if (parent.dryRun) {
                System.out.println("  注意：本次以 --dry-run 启动，页面上的执行按钮同样只列清单不下载。");
            }
            System.out.println("  按 Ctrl+C 退出。");
            if (!noBrowser) {
                openBrowser(url);
            }
            // 阻塞直到进程被 Ctrl+C 结束
            Thread.currentThread().join();
            return ExitCodes.OK;
        } catch (IOException e) {
            System.err.println("[失败] 端口 " + port + " 启动失败：" + e.getMessage());
            return ExitCodes.GENERAL;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("操作台已停止。");
            return ExitCodes.OK;
        } finally {
            server.close();
        }
    }

    private void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (RuntimeException | IOException e) {
            System.out.println("（未能自动打开浏览器，请手动访问上面的地址）");
        }
    }
}
