package com.xmedia.downloader.cli;

import com.xmedia.downloader.config.AppConfig;
import com.xmedia.downloader.config.ConfigException;
import com.xmedia.downloader.error.AuthException;
import com.xmedia.downloader.error.OperationInvalidException;
import com.xmedia.downloader.error.QuotaExhaustedException;
import com.xmedia.downloader.model.TaskResult;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/**
 * 任务类子命令的公共骨架：统一的选项（--limit / --fresh / --dump-pages）、配置加载与错误→退出码映射。
 * 子类只需实现 {@link #executeTolerant(AppBootstrap, AppConfig)}。
 */
public abstract class AbstractDownloadCommand implements Callable<Integer> {

    @ParentCommand
    protected MainCommand parent;

    @Spec
    protected CommandSpec spec;

    @Option(names = "--limit", paramLabel = "N", description = "最多处理 N 条推文（0=不限）")
    protected int limitTweets = 0;

    @Option(names = "--fresh", description = "忽略增量水位线，从头扫描（已成功的媒体仍不会重复下载）")
    protected boolean fresh;

    @Option(names = "--dump-pages", paramLabel = "DIR", description = "把每页原始 JSON 导出到 DIR（排查用）")
    protected String dumpPagesDir;

    /** 本子命令要处理的目标名（单个），由主流程循环调用。 */
    protected abstract String action();

    /** 执行一个目标；返回该目标的汇总。 */
    protected abstract TaskResult runOne(AppBootstrap boot, String target);

    @Override
    public Integer call() {
        AppConfig config;
        try {
            config = parent.loadConfig();
        } catch (ConfigException e) {
            System.err.println("[配置错误] " + e.getMessage());
            return ExitCodes.CONFIG;
        }
        int exit = ExitCodes.OK;
        try (AppBootstrap boot = AppBootstrap.create(config, parent.operationsOverride())) {
            for (String target : targets()) {
                System.out.println("=== " + action() + "：" + target + " ===");
                TaskResult result = runOne(boot, target);
                printSummary(target, result);
                if (result.status() == TaskResult.Status.ABORTED) {
                    exit = ExitCodes.QUOTA;
                }
            }
            return exit;
        } catch (QuotaExhaustedException e) {
            System.err.println("[配额不足] " + e.getMessage());
            return ExitCodes.QUOTA;
        } catch (AuthException e) {
            System.err.println("[Cookie/认证失败] " + e.getMessage());
            return ExitCodes.AUTH;
        } catch (OperationInvalidException e) {
            System.err.println("[操作失效] " + e.getMessage());
            return ExitCodes.GENERAL;
        } catch (IllegalArgumentException e) {
            System.err.println("[参数错误] " + e.getMessage());
            return ExitCodes.USAGE;
        } catch (RuntimeException e) {
            System.err.println("[失败] " + e.getClass().getSimpleName() + "：" + e.getMessage());
            return ExitCodes.GENERAL;
        }
    }

    /** 待处理目标列表（单目标命令返回单元素列表）。 */
    protected abstract java.util.List<String> targets();

    private void printSummary(String target, TaskResult result) {
        System.out.printf("--- %s 完成：%s 成功 %d / 失败 %d / 跳过 %d，API 调用 %d 次，耗时 %.1fs%n",
                target, result.status(), result.successCount(), result.failedCount(),
                result.skippedCount(), result.apiCallCount(), result.elapsedMillis() / 1000.0);
        if (!result.failedItems().isEmpty()) {
            System.out.println("    失败明细（最多列 10 条）：");
            result.failedItems().stream().limit(10).forEach(f -> System.out.println("      " + f));
        }
        if (result.status() == TaskResult.Status.ABORTED) {
            System.out.println("    配额耗尽已停机，续跑点：" + result.resumeCursor());
        }
    }

    protected java.nio.file.Path dumpDir() {
        return dumpPagesDir == null ? null : java.nio.file.Path.of(dumpPagesDir);
    }

    /** 供子类拼 -c 覆盖项时使用。 */
    protected Map<String, String> cliOverrides() {
        return parent.cliOverrides();
    }
}
