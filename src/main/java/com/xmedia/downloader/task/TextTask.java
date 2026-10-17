package com.xmedia.downloader.task;

import com.xmedia.downloader.account.Account;
import com.xmedia.downloader.account.AccountPool;
import com.xmedia.downloader.client.TimelineParser;
import com.xmedia.downloader.client.UserApi;
import com.xmedia.downloader.config.AppConfig;
import com.xmedia.downloader.downloader.MediaDownloader;
import com.xmedia.downloader.model.TaskType;
import com.xmedia.downloader.model.Tweet;
import com.xmedia.downloader.model.User;
import com.xmedia.downloader.ratelimit.QuotaLedger;
import com.xmedia.downloader.storage.DownloadRecordRepository;
import com.xmedia.downloader.storage.SyncCursorRepository;
import com.xmedia.downloader.storage.TaskRunRepository;
import java.nio.file.Path;

/**
 * 纯文本任务（F9）：只抓推文文本，不下载媒体，产物在 {@code <用户名>/text/*.txt}。
 *
 * <p>复用 UserTweets 接口；增量靠水位线（已抓过的推文不再重复写文件）。
 */
public final class TextTask extends AbstractTimelineTask {

    private final UserApi api;
    private User user;

    public TextTask(AppConfig config, AccountPool pool, UserApi api, TimelineParser parser,
                    MediaDownloader downloader, DownloadRecordRepository records,
                    SyncCursorRepository cursors, TaskRunRepository taskRuns, QuotaLedger quota) {
        super(config, pool, parser, downloader, records, cursors, taskRuns, quota);
        this.api = api;
    }

    @Override
    protected TaskType taskType() {
        return TaskType.TEXT;
    }

    @Override
    protected int pageSize() {
        return config.tasks().text().pageSize();
    }

    @Override
    protected boolean downloadsMedia() {
        return false;
    }

    @Override
    protected Target prepare(Account account, String screenName) {
        this.user = api.resolve(account, screenName);
        System.out.printf("用户 @%s（%s）rest_id=%s 推文数=%d%n",
                user.screenName(), user.name(), user.restId(), user.statusesCount());
        return new Target("@" + user.screenName(), user.restId());
    }

    @Override
    protected TimelineParser.Page fetchPage(Account account, String target, String cursor) {
        return parser.parse(api.tweetsPage(account, user, cursor, pageSize()));
    }

    @Override
    protected void onTweet(Tweet tweet, Target target) {
        Path file = TextWriter.write(saveRoot.resolve(user.screenName()).resolve("text"), tweet, true);
        if (file != null) {
            log.info("文本已保存：{}", file.getFileName());
        }
    }
}
