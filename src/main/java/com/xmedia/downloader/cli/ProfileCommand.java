package com.xmedia.downloader.cli;

import com.xmedia.downloader.model.TaskResult;
import com.xmedia.downloader.task.ProfileTask;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code profile} 子命令：下载用户主页信息（头像 / banner / 简介，F10）。 */
@Command(name = "profile", mixinStandardHelpOptions = true,
        description = "下载用户主页信息（头像 / banner / 简介）到 <用户名>/profile/")
public final class ProfileCommand extends AbstractDownloadCommand {

    @Option(names = {"-u", "--user"}, required = true, paramLabel = "SCREEN_NAME",
            description = "用户 screen_name，可重复指定")
    List<String> users = new ArrayList<>();

    @Override
    protected String action() {
        return "PROFILE";
    }

    @Override
    protected List<String> targets() {
        return users;
    }

    @Override
    protected TaskResult runOne(AppBootstrap boot, String target) {
        return boot.profileTask().run(target, new ProfileTask.Options(parent.dryRun));
    }
}
