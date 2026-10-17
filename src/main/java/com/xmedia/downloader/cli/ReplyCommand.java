package com.xmedia.downloader.cli;

import com.xmedia.downloader.model.TaskResult;
import com.xmedia.downloader.task.AbstractTimelineTask;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code reply} 子命令：下载指定推文及其评论区的媒体（F8）。 */
@Command(name = "reply", mixinStandardHelpOptions = true,
        description = "下载指定推文评论区（含该推文）的媒体；目标可给链接或纯 ID")
public final class ReplyCommand extends AbstractDownloadCommand {

    @Option(names = {"-r", "--reply"}, required = true, paramLabel = "URL_OR_ID",
            description = "推文链接（https://x.com/user/status/123…）或纯推文 ID，可重复")
    List<String> replies = new ArrayList<>();

    @Override
    protected String action() {
        return "REPLY";
    }

    @Override
    protected List<String> targets() {
        return replies;
    }

    @Override
    protected TaskResult runOne(AppBootstrap boot, String target) {
        return boot.replyTask().run(target,
                new AbstractTimelineTask.Options(limitTweets, parent.dryRun, fresh, dumpDir()));
    }
}
