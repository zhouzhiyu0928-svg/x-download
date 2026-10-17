package com.xmedia.downloader.cli;

import com.xmedia.downloader.model.TaskResult;
import com.xmedia.downloader.task.AbstractTimelineTask;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code text} 子命令：只抓推文文本，不下载媒体（F9）。 */
@Command(name = "text", mixinStandardHelpOptions = true,
        description = "仅获取指定用户的纯文本推文（保存到 <用户名>/text/*.txt）")
public final class TextCommand extends AbstractDownloadCommand {

    @Option(names = {"-u", "--user"}, required = true, paramLabel = "SCREEN_NAME",
            description = "用户 screen_name，可重复指定")
    List<String> users = new ArrayList<>();

    @Override
    protected String action() {
        return "TEXT";
    }

    @Override
    protected List<String> targets() {
        return users;
    }

    @Override
    protected TaskResult runOne(AppBootstrap boot, String target) {
        return boot.textTask().run(target,
                new AbstractTimelineTask.Options(limitTweets, parent.dryRun, fresh, dumpDir()));
    }
}
