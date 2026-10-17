package com.xmedia.downloader.cli;

import com.xmedia.downloader.client.QueryIdRefresher;
import com.xmedia.downloader.config.AppConfig;
import com.xmedia.downloader.config.ConfigException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * {@code refresh-operations} 子命令：把 operations.yaml 里的 queryId 更新为当前值。
 *
 * <p>应对「X 接口变更」风险：X 会不定期轮换 GraphQL queryId，表现为 HTTP 404（空响应体）。
 * 本命令从 X 经典客户端的 main.js 里解析最新「操作名 → queryId」表并回写，改前自动备份；
 * 加 {@code --dry-run} 则只显示会改哪些、不落盘。
 */
@Command(name = "refresh-operations", mixinStandardHelpOptions = true,
        description = "从 X 客户端 bundle 拉取最新 queryId 并回写 operations.yaml（配合 --dry-run 可先预览）")
public final class RefreshOperationsCommand implements Callable<Integer> {

    @ParentCommand
    MainCommand parent;

    @Option(names = {"-o", "--out"}, paramLabel = "FILE",
            description = "目标 operations.yaml；默认自动定位（源码目录副本优先，其次当前目录）")
    String out;

    @Override
    public Integer call() {
        AppConfig config;
        try {
            config = parent.loadConfig();
        } catch (ConfigException e) {
            System.err.println("[配置错误] " + e.getMessage());
            return ExitCodes.CONFIG;
        }
        Path target = resolveTarget();
        System.out.println("目标文件：" + target.toAbsolutePath()
                + (parent.dryRun ? "（dry-run：只预览不落盘）" : ""));
        try (AppBootstrap boot = AppBootstrap.create(config, parent.operationsOverride())) {
            QueryIdRefresher.Result result = QueryIdRefresher.refresh(
                    boot.client().httpClient(), boot.accountPool().accounts().get(0),
                    target, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36",
                    !parent.dryRun);
            if (result.updated().isEmpty()) {
                System.out.println("所有 queryId 均为最新，无需修改。");
            } else {
                System.out.println("已更新 " + result.updated().size() + " 个操作：");
                result.updated().forEach((op, change) -> System.out.println("  " + op + "：" + change));
                if (result.backup() != null) {
                    System.out.println("原文件已备份：" + result.backup().toAbsolutePath());
                }
            }
            if (!result.notFound().isEmpty()) {
                System.out.println("未在 bundle 中找到（保留原值）：" + result.notFound());
            }
            return ExitCodes.OK;
        } catch (IOException e) {
            System.err.println("[失败] 拉取/解析客户端 bundle 失败：" + e.getMessage());
            return ExitCodes.GENERAL;
        } catch (RuntimeException e) {
            System.err.println("[失败] " + e.getClass().getSimpleName() + "：" + e.getMessage());
            return ExitCodes.GENERAL;
        }
    }

    /** 默认优先改源码目录里的副本（开发态），否则改当前目录的 operations.yaml。 */
    private Path resolveTarget() {
        if (out != null && !out.isBlank()) {
            return Path.of(out);
        }
        Path dev = Path.of("src", "main", "resources", "operations.yaml");
        if (Files.exists(dev)) {
            return dev;
        }
        return Path.of(parent.operationsOverride() != null
                ? parent.operationsOverride().toString() : "operations.yaml");
    }
}
