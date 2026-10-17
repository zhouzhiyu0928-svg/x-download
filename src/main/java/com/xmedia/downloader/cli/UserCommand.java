package com.xmedia.downloader.cli;

import com.xmedia.downloader.config.AppConfig;
import com.xmedia.downloader.config.ConfigException;
import com.xmedia.downloader.task.UserMediaTask;
import com.xmedia.downloader.model.TaskResult;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code user} 子命令：下载指定用户的媒体时间线（F1～F6）。 */
@Command(name = "user", mixinStandardHelpOptions = true,
        description = "下载指定用户（screen_name）的媒体；--dry-run 只列清单不下载")
public final class UserCommand extends AbstractDownloadCommand {

    @Option(names = {"-u", "--user"}, required = true, paramLabel = "SCREEN_NAME",
            description = "用户 screen_name，可重复指定")
    List<String> users = new ArrayList<>();

    @Override
    protected String action() {
        return "USER_MEDIA";
    }

    @Override
    protected List<String> targets() {
        return users;
    }

    @Override
    protected TaskResult runOne(AppBootstrap boot, String target) {
        return boot.userMediaTask().run(target, new UserMediaTask.Options(limitTweets, parent.dryRun, fresh, dumpDir()));
    }
}
