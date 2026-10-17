package com.xmedia.downloader.cli;

import com.xmedia.downloader.config.AppConfig;
import com.xmedia.downloader.config.ConfigException;
import com.xmedia.downloader.config.ConfigLoader;
import com.xmedia.downloader.error.QuotaExhaustedException;
import com.xmedia.downloader.logging.Logging;
import com.xmedia.downloader.ratelimit.QuotaLedger;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * 根命令：注册子命令（user / tag / reply / text / profile）。
 *
 * <p>R1 交付：配置加载 + 数据库装配 + {@code --dry-run}（只校验、只估算，不发起任何网络请求）。
 * 子命令与网络层在 R2/R3 接入（见 review/R1_任务拆解.md）。
 */
@Command(
        name = "x-media-downloader",
        mixinStandardHelpOptions = true,
        version = "x-media-downloader 0.1.0-SNAPSHOT",
        description = "Twitter/X 媒体下载器（本地工具，仅限个人学习与研究使用）。",
        subcommands = {UserCommand.class, TagCommand.class, ReplyCommand.class,
                TextCommand.class, ProfileCommand.class, ReportCommand.class, ConsoleCommand.class,
                RefreshOperationsCommand.class, TargetsCommand.class})
public class MainCommand implements Callable<Integer> {

    @Option(names = {"-c", "--config"}, paramLabel = "FILE",
            description = "配置文件路径（YAML）。默认：${DEFAULT-VALUE}")
    String configPath = "config.yaml";

    @Option(names = "--dry-run",
            description = "只加载并校验配置、装配数据库、估算调用配额；不发起任何网络请求")
    boolean dryRun;

    @Option(names = "--save-path", description = "覆盖 save-path（CLI 优先级最高）")
    String savePath;

    @Option(names = "--db-path", description = "覆盖 db-path")
    String dbPath;

    @Option(names = "--max-concurrent", description = "覆盖 download.max-concurrent")
    Integer maxConcurrent;

    @Option(names = "--naming-template", description = "覆盖 download.naming-template")
    String namingTemplate;

    @Option(names = "--tweets", description = "预计拉取的推文条数；给出时会做「启动前配额估算」")
    Long tweets;

    @Option(names = "--operations", paramLabel = "FILE",
            description = "覆盖内置 operations.yaml（queryId 热更新用）")
    String operationsPath;

    /** 供子命令复用：加载配置（CLI ＞ 环境变量 ＞ 文件 ＞ 默认），并按配置应用日志。 */
    public AppConfig loadConfig() {
        AppConfig config = new ConfigLoader().load(configPath, System.getenv(), cliOverrides());
        Logging.apply(config.log());
        return config;
    }

    public Path operationsOverride() {
        return operationsPath == null || operationsPath.isBlank() ? null : Path.of(operationsPath);
    }

    @Override
    public Integer call() {
        AppConfig config;
        try {
            config = loadConfig();
        } catch (ConfigException e) {
            System.err.println("[配置错误] " + e.getMessage());
            return ExitCodes.CONFIG;
        }

        try (AppBootstrap boot = AppBootstrap.create(config, operationsOverride())) {
            printConfigSummary(boot.config());
            printDatabaseCensus(boot);
            printQuotaEstimate(boot);
            if (dryRun) {
                System.out.println();
                System.out.println("dry-run：未发起任何网络请求。");
            }
            return ExitCodes.OK;
        } catch (QuotaExhaustedException e) {
            System.err.println("[配额不足] " + e.getMessage());
            return ExitCodes.QUOTA;
        } catch (RuntimeException e) {
            System.err.println("[失败] " + e.getMessage());
            return ExitCodes.GENERAL;
        }
    }

    /** CLI 覆盖项（点号键；优先级最高）。 */
    public Map<String, String> cliOverrides() {
        Map<String, String> cli = new LinkedHashMap<>();
        if (savePath != null) {
            cli.put("save-path", savePath);
        }
        if (dbPath != null) {
            cli.put("db-path", dbPath);
        }
        if (maxConcurrent != null) {
            cli.put("download.max-concurrent", maxConcurrent.toString());
        }
        if (namingTemplate != null) {
            cli.put("download.naming-template", namingTemplate);
        }
        return cli;
    }

    private void printConfigSummary(AppConfig c) {
        System.out.println("=== 配置 ===");
        System.out.println("  保存目录   : " + c.savePath());
        System.out.println("  数据库     : " + c.dbPath());
        System.out.println("  日志       : level=" + c.log().level() + " file=" + c.log().file()
                + " max-history=" + c.log().maxHistory());
        System.out.println("  账号       : " + c.accounts().size() + " 个 → "
                + c.accounts().stream().map(a -> a.name() + (a.note().isBlank() ? "" : "(本人)")).toList());
        System.out.println("  代理       : " + (c.proxy().enabled()
                ? c.proxy().mode() + " " + c.proxy().firstOrNull() : "未启用"));
        System.out.println("  并发/间隔  : " + c.download().maxConcurrent() + " / "
                + c.rateLimit().requestIntervalMs() + "ms，最大重试 " + c.rateLimit().maxRetries());
        System.out.println("  命名模板   : " + c.download().namingTemplate());
        System.out.println("  增量/续传  : incremental=" + c.download().enableIncremental()
                + " resume=" + c.download().resumeEnabled() + " video-variant=" + c.download().videoVariant());
        System.out.println("  配额       : 每账号每日 " + c.quota().perAccountDaily()
                + " 次，预留重试 " + c.quota().reserveForRetry()
                + "，估算系数 " + c.quota().estimateTweetsPerCall() + " 条/次");
    }

    private void printDatabaseCensus(AppBootstrap boot) {
        Map<String, Object> census = boot.database().census();
        System.out.println("=== 数据库 ===");
        System.out.println("  文件       : " + boot.database().file());
        System.out.println("  结构版本   : " + boot.database().schemaVersion());
        System.out.println("  journal    : " + census.get("journalMode") + "（期望 wal）");
        Object tables = census.get("tables");
        System.out.println("  表         : " + tables);
    }

    private void printQuotaEstimate(AppBootstrap boot) {
        System.out.println("=== 配额 ===");
        int perCall = boot.config().quota().estimateTweetsPerCall();
        int total = boot.accountPool().totalRemainingQuota();
        System.out.println("  可用配额   : " + total + " 次调用（≈ " + (long) total * perCall + " 条推文）");
        if (tweets != null && tweets > 0) {
            QuotaLedger.Estimate est = boot.quotaLedger().estimate(tweets, boot.accountPool());
            System.out.printf("  估算       : 推文 %d 条 ÷ %d 条/次 ⇒ 需要 %d 次调用；可用 %d 次 ⇒ %s%n",
                    est.tweets(), perCall, est.callsNeeded(), est.quotaAvailable(),
                    est.sufficient() ? "充足" : "不足（至少需要 " + est.accountsNeeded() + " 个可用账号）");
            boot.quotaLedger().assertCanRun(est);
        }
    }
}
