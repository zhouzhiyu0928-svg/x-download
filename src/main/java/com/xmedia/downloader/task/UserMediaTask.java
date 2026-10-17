package com.xmedia.downloader.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.xmedia.downloader.account.Account;
import com.xmedia.downloader.account.AccountPool;
import com.xmedia.downloader.client.TimelineParser;
import com.xmedia.downloader.client.UserApi;
import com.xmedia.downloader.config.AppConfig;
import com.xmedia.downloader.downloader.MediaDownloader;
import com.xmedia.downloader.model.TaskType;
import com.xmedia.downloader.model.User;
import com.xmedia.downloader.ratelimit.QuotaLedger;
import com.xmedia.downloader.storage.DownloadRecordRepository;
import com.xmedia.downloader.storage.SyncCursorRepository;
import com.xmedia.downloader.storage.TaskRunRepository;

/** 用户媒体任务（F1～F6，阶段一核心）：按 screen_name 下载该用户媒体时间线。 */
public final class UserMediaTask extends AbstractTimelineTask {

    private final UserApi api;
    private User user;

    public UserMediaTask(AppConfig config, AccountPool pool, UserApi api, TimelineParser parser,
                         MediaDownloader downloader, DownloadRecordRepository records,
                         SyncCursorRepository cursors, TaskRunRepository taskRuns, QuotaLedger quota) {
        super(config, pool, parser, downloader, records, cursors, taskRuns, quota);
        this.api = api;
    }

    @Override
    protected TaskType taskType() {
        return TaskType.USER_MEDIA;
    }

    @Override
    protected int pageSize() {
        return 500;
    }

    @Override
    protected Target prepare(Account account, String screenName) {
        this.user = api.resolve(account, screenName);
        System.out.printf("用户 @%s（%s）rest_id=%s 媒体数=%d 推文数=%d%n",
                user.screenName(), user.name(), user.restId(), user.mediaCount(), user.statusesCount());
        return new Target("@" + user.screenName(), user.restId());
    }

    @Override
    protected TimelineParser.Page fetchPage(Account account, String target, String cursor) {
        JsonNode page = api.mediaPage(account, user, cursor, null);
        return parser.parse(page);
    }
}
