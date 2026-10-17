package com.xmedia.downloader.cli;

import com.xmedia.downloader.model.TaskResult;
import com.xmedia.downloader.task.AbstractTimelineTask;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code tag} 子命令：按 Tag / 高级搜索下载（F7）。 */
@Command(name = "tag", mixinStandardHelpOptions = true,
        description = "按 Tag 或高级搜索条件下载媒体（过滤词与 product 见配置 tasks.tag）")
public final class TagCommand extends AbstractDownloadCommand {

    @Option(names = {"-t", "--tag"}, required = true, paramLabel = "TAG_OR_QUERY",
            description = "Tag / 搜索词（可带 # 前缀），可重复指定")
    List<String> tags = new ArrayList<>();

    @Override
    protected String action() {
        return "TAG";
    }

    @Override
    protected List<String> targets() {
        return tags;
    }

    @Override
    protected TaskResult runOne(AppBootstrap boot, String target) {
        return boot.tagSearchTask().run(target,
                new AbstractTimelineTask.Options(limitTweets, parent.dryRun, fresh, dumpDir()));
    }
}
