package com.xmedia.downloader.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.xmedia.downloader.account.Account;
import com.xmedia.downloader.account.AccountPool;
import com.xmedia.downloader.client.TimelineParser;
import com.xmedia.downloader.client.UserApi;
import com.xmedia.downloader.config.AppConfig;
import com.xmedia.downloader.downloader.MediaDownloader;
import com.xmedia.downloader.model.TaskType;
import com.xmedia.downloader.ratelimit.QuotaLedger;
import com.xmedia.downloader.storage.DownloadRecordRepository;
import com.xmedia.downloader.storage.SyncCursorRepository;
import com.xmedia.downloader.storage.TaskRunRepository;
import java.nio.file.Path;

/**
 * 评论区任务（F8）：TweetDetail 翻页下载该推文及其评论区的媒体。
 *
 * <p>视频开关随 {@code tasks.reply.include-video}（与全局 has-video 取与），因为评论区常是图文为主。
 * 产物落在 {@code reply/<推文ID>/<评论者>/...}。
 */
public final class ReplyTask extends AbstractTimelineTask {

    private final UserApi api;
    private final TimelineParser replyParser;
    private String tweetId;

    public ReplyTask(AppConfig config, AccountPool pool, UserApi api, TimelineParser parser,
                     MediaDownloader downloader, DownloadRecordRepository records,
                     SyncCursorRepository cursors, TaskRunRepository taskRuns, QuotaLedger quota) {
        super(config, pool, parser, downloader, records, cursors, taskRuns, quota);
        this.api = api;
        this.replyParser = new TimelineParser(
                config.download().hasVideo() && config.tasks().reply().includeVideo(),
                config.download().hasRetweet());
    }

    @Override
    protected TaskType taskType() {
        return TaskType.REPLY;
    }

    @Override
    protected int pageSize() {
        return config.tasks().reply().pageSize();
    }

    @Override
    protected Target prepare(Account account, String input) {
        tweetId = UserApi.extractTweetId(input);
        System.out.printf("评论区目标推文：%s（含视频=%s，每页 %d 条）%n",
                tweetId, config.tasks().reply().includeVideo() ? "是" : "否", pageSize());
        return new Target("推文 " + tweetId, "REPLY:" + tweetId);
    }

    @Override
    protected TimelineParser.Page fetchPage(Account account, String target, String cursor) {
        JsonNode page = api.tweetDetail(account, tweetId, cursor);
        return replyParser.parse(page);
    }

    @Override
    protected Path downloadRoot() {
        return saveRoot.resolve("reply").resolve(tweetId);
    }
}
