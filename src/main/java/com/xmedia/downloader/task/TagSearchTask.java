package com.xmedia.downloader.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.xmedia.downloader.account.Account;
import com.xmedia.downloader.account.AccountPool;
import com.xmedia.downloader.client.TimelineParser;
import com.xmedia.downloader.client.UserApi;
import com.xmedia.downloader.config.AppConfig;
import com.xmedia.downloader.downloader.MediaDownloader;
import com.xmedia.downloader.model.TaskType;
import com.xmedia.downloader.model.Tweet;
import com.xmedia.downloader.ratelimit.QuotaLedger;
import com.xmedia.downloader.storage.DownloadRecordRepository;
import com.xmedia.downloader.storage.SyncCursorRepository;
import com.xmedia.downloader.storage.TaskRunRepository;
import com.xmedia.downloader.util.PathSanitizer;
import java.nio.file.Path;

/**
 * Tag / 高级搜索任务（F7）：SearchTimeline 翻页下载媒体，可选同时保存文本。
 *
 * <p>按查询词单独建目录 {@code tag/<查询词>/<作者>/...}，避免与用户任务混在一起。
 */
public final class TagSearchTask extends AbstractTimelineTask {

    private final UserApi api;
    private final AppConfig.TasksConfig.TagConfig tagConfig;
    private String rawQuery;
    private String queryLabel;

    public TagSearchTask(AppConfig config, AccountPool pool, UserApi api, TimelineParser parser,
                         MediaDownloader downloader, DownloadRecordRepository records,
                         SyncCursorRepository cursors, TaskRunRepository taskRuns, QuotaLedger quota) {
        super(config, pool, parser, downloader, records, cursors, taskRuns, quota);
        this.api = api;
        this.tagConfig = config.tasks().tag();
    }

    @Override
    protected TaskType taskType() {
        return TaskType.TAG;
    }

    @Override
    protected int pageSize() {
        return tagConfig.pageSize();
    }

    @Override
    protected Target prepare(Account account, String tag) {
        if (tag == null || tag.isBlank()) {
            throw new IllegalArgumentException("Tag / 搜索词不能为空");
        }
        String trimmed = tag.trim();
        // 裸词按 Tag 处理（补 #）；带 # / @ 前缀或含 ':'、空格的高级搜索语句（如 from:user filter:images）原样使用
        boolean advanced = trimmed.startsWith("#") || trimmed.startsWith("@")
                || trimmed.contains(":") || trimmed.contains(" ");
        queryLabel = advanced ? trimmed : "#" + trimmed;
        rawQuery = tagConfig.filter().isBlank() ? queryLabel : queryLabel + " " + tagConfig.filter();
        System.out.printf("搜索: %s（product=%s，每页 %d 条，保存文本=%s）%n",
                rawQuery, tagConfig.product(), pageSize(), tagConfig.saveText() ? "是" : "否");
        // 水位线键带查询词：同一 Tag 的增量互不干扰
        return new Target(queryLabel, "TAG:" + rawQuery);
    }

    @Override
    protected TimelineParser.Page fetchPage(Account account, String target, String cursor) {
        JsonNode page = api.searchTimeline(account, rawQuery, cursor, pageSize(), tagConfig.product());
        return parser.parse(page);
    }

    @Override
    protected Path downloadRoot() {
        return saveRoot.resolve("tag").resolve(PathSanitizer.sanitizeSegment(rawQuery.replace(' ', '_')));
    }

    @Override
    protected void onTweet(Tweet tweet, Target target) {
        if (tagConfig.saveText()) {
            TextWriter.write(downloadRoot().resolve("text"), tweet, true);
        }
    }
}
