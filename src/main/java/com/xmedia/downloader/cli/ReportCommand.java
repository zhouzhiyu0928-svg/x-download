package com.xmedia.downloader.cli;

import com.xmedia.downloader.config.AppConfig;
import com.xmedia.downloader.config.ConfigException;
import com.xmedia.downloader.report.ReportGenerator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/** {@code report} 子命令：从本地数据库导出统计报告（F11）。 */
@Command(name = "report", mixinStandardHelpOptions = true,
        description = "导出下载统计报告（CSV / Markdown）")
public final class ReportCommand implements Callable<Integer> {

    @ParentCommand
    MainCommand parent;

    @Option(names = "--format", split = ",", defaultValue = "csv,md",
            description = "输出格式，逗号分隔：csv,md（默认 ${DEFAULT-VALUE}）")
    List<String> formats;

    @Option(names = "--out", paramLabel = "DIR", defaultValue = "./reports",
            description = "输出目录（默认 ${DEFAULT-VALUE}）")
    String outDir;

    @Option(names = "--failure-limit", defaultValue = "50",
            description = "Markdown 里最多列出的失败明细条数（默认 ${DEFAULT-VALUE}）")
    int failureLimit;

    @Override
    public Integer call() {
        AppConfig config;
        try {
            config = parent.loadConfig();
        } catch (ConfigException e) {
            System.err.println("[配置错误] " + e.getMessage());
            return ExitCodes.CONFIG;
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (String f : formats) {
            String v = f.trim().toLowerCase();
            if (!v.equals("csv") && !v.equals("md") && !v.equals("markdown")) {
                System.err.println("[参数错误] 不支持的格式：" + f + "（只支持 csv / md）");
                return ExitCodes.USAGE;
            }
            wanted.add(v.startsWith("md") ? "md" : "csv");
        }
        try (AppBootstrap boot = AppBootstrap.create(config, parent.operationsOverride())) {
            ReportGenerator.Report report = new ReportGenerator(boot.database())
                    .generate(Path.of(outDir), wanted, failureLimit);
            System.out.printf("报告已生成：记录 %d 条、任务 %d 次%n", report.recordCount(), report.taskRunCount());
            if (report.csv() != null) {
                System.out.println("  CSV      : " + report.csv().toAbsolutePath());
            }
            if (report.markdown() != null) {
                System.out.println("  Markdown : " + report.markdown().toAbsolutePath());
            }
            return ExitCodes.OK;
        } catch (IOException e) {
            System.err.println("[失败] 报告写入失败：" + e.getMessage());
            return ExitCodes.GENERAL;
        } catch (RuntimeException e) {
            System.err.println("[失败] " + e.getClass().getSimpleName() + "：" + e.getMessage());
            return ExitCodes.GENERAL;
        }
    }
}
