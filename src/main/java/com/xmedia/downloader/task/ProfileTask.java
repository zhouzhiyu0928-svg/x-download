package com.xmedia.downloader.task;

import com.xmedia.downloader.account.Account;
import com.xmedia.downloader.account.AccountPool;
import com.xmedia.downloader.client.TimelineParser;
import com.xmedia.downloader.client.UserApi;
import com.xmedia.downloader.config.AppConfig;
import com.xmedia.downloader.downloader.MediaDownloader;
import com.xmedia.downloader.model.DownloadRecord;
import com.xmedia.downloader.model.MediaItem;
import com.xmedia.downloader.model.MediaType;
import com.xmedia.downloader.model.RecordStatus;
import com.xmedia.downloader.model.TaskResult;
import com.xmedia.downloader.model.TaskType;
import com.xmedia.downloader.model.UserProfile;
import com.xmedia.downloader.ratelimit.QuotaLedger;
import com.xmedia.downloader.storage.DownloadRecordRepository;
import com.xmedia.downloader.storage.TaskRunRepository;
import com.xmedia.downloader.util.NamingTemplate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 主页信息任务（F10）：头像 / banner / 简介。
 *
 * <p>不是时间线任务，故不继承 {@link AbstractTimelineTask}；产物固定在
 * {@code <用户名>/profile/}（{@code <用户名>_avatar.jpg}、{@code <用户名>_banner.jpg}、{@code description.txt}）。
 * 头像会把 URL 的 {@code _normal} 升级为 {@code _400x400}（原版做法）。
 */
public final class ProfileTask {

    private static final Logger log = LoggerFactory.getLogger(ProfileTask.class);

    public record Options(boolean dryRun) {
    }

    private final AppConfig config;
    private final AccountPool pool;
    private final UserApi api;
    private final MediaDownloader assetDownloader;
    private final DownloadRecordRepository records;
    private final TaskRunRepository taskRuns;
    private final QuotaLedger quota;
    private final Path saveRoot;

    public ProfileTask(AppConfig config, AccountPool pool, UserApi api, OkHttpClient http,
                       DownloadRecordRepository records, TaskRunRepository taskRuns, QuotaLedger quota) {
        this.config = config;
        this.pool = pool;
        this.api = api;
        this.records = records;
        this.taskRuns = taskRuns;
        this.quota = quota;
        this.saveRoot = Path.of(config.savePath());
        // 主页资产的命名与媒体时间线不同，单独一套模板
        this.assetDownloader = new MediaDownloader(http, config.download(),
                new NamingTemplate("{user}/profile/{mediaKey}.{ext}"));
    }

    public TaskResult run(String screenName, Options options) {
        long startedAt = System.currentTimeMillis();
        Account account = pool.acquire();
        UserProfile profile = api.profile(account, screenName);
        System.out.printf("=== 开始处理 @%s（PROFILE） ===%n", profile.screenName());
        System.out.printf("昵称=%s 推文数=%d 媒体数=%d banner=%s%n", profile.name(),
                profile.statusesCount(), profile.mediaCount(), profile.bannerUrl() == null ? "无" : "有");

        // dry-run 保持零状态（与时间线任务一致）
        long runId = options.dryRun() ? -1L : taskRuns.start(TaskType.PROFILE.name(), screenName);
        int success = 0;
        int failed = 0;
        int skipped = 0;
        List<String> failures = new ArrayList<>();
        AppConfig.TasksConfig.ProfileConfig pc = config.tasks().profile();
        Path dir = saveRoot.resolve(profile.screenName()).resolve("profile");

        if (options.dryRun()) {
            System.out.println("  [列出] 头像 " + profile.avatarUrl());
            if (profile.bannerUrl() != null) {
                System.out.println("  [列出] banner " + profile.bannerUrl());
            }
            System.out.println("  [列出] 简介（" + profile.description().length() + " 字）");
            skipped = 1;
            if (runId > 0) {
                taskRuns.finish(runId, TaskResult.Status.SUCCESS, skipped, 0, 0, skipped, 1,
                        System.currentTimeMillis() - startedAt, "dry-run");
            }
            log.info("dry-run：未写入任何文件与记录");
            return new TaskResult("PROFILE:@" + profile.screenName(), TaskResult.Status.SUCCESS,
                    0, 0, skipped, System.currentTimeMillis() - startedAt, List.of(), null, 1);
        }

        if (pc.downloadAvatar() && profile.avatarUrl() != null && !profile.avatarUrl().isBlank()) {
            MediaItem avatar = new MediaItem(profile.restId(), "avatar", profile.screenName(), MediaType.IMAGE,
                    profile.avatarUrl(), TimelineParser.photoExtension(profile.avatarUrl()), 0L, null);
            MediaDownloader.Outcome outcome = assetDownloader.download(avatar, saveRoot, 0);
            recordAsset(profile, avatar, outcome);
            if (outcome.success()) {
                success++;
                log.info("头像已保存：{}（{} 字节）", outcome.path(), outcome.bytes());
            } else {
                failed++;
                failures.add("头像 ← " + outcome.error());
            }
        }
        if (pc.downloadBanner() && profile.bannerUrl() != null) {
            // banner 地址形如 …/<id>/<ts>/1500x500（无扩展名），后缀直接按 X 的实拍格式取 jpg
            MediaItem banner = new MediaItem(profile.restId(), "banner", profile.screenName(), MediaType.IMAGE,
                    profile.bannerUrl(), "jpg", 0L, null);
            MediaDownloader.Outcome outcome = assetDownloader.download(banner, saveRoot, 0);
            recordAsset(profile, banner, outcome);
            if (outcome.success()) {
                success++;
                log.info("banner 已保存：{}（{} 字节）", outcome.path(), outcome.bytes());
            } else {
                failed++;
                failures.add("banner ← " + outcome.error());
            }
        }
        if (pc.saveDescription()) {
            try {
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("description.txt"), profile.description(), StandardCharsets.UTF_8);
                success++;
                log.info("简介已保存：{}（{} 字）", dir.resolve("description.txt"), profile.description().length());
            } catch (IOException e) {
                failed++;
                failures.add("简介 ← " + e.getMessage());
            }
        }

        long elapsed = System.currentTimeMillis() - startedAt;
        TaskResult.Status status = failed == 0 ? TaskResult.Status.SUCCESS
                : (success > 0 ? TaskResult.Status.PARTIAL : TaskResult.Status.FAILED);
        if (runId > 0) {
            taskRuns.finish(runId, status, success + failed, success, failed, 0, 1, elapsed, "");
        }
        return new TaskResult("PROFILE:@" + profile.screenName(), status, success, failed, skipped, elapsed,
                failures, null, 1);
    }

    private void recordAsset(UserProfile profile, MediaItem item, MediaDownloader.Outcome outcome) {
        records.upsert(new DownloadRecord(profile.restId(), item.mediaKey(), item.type(), item.url(),
                profile.restId(), profile.screenName(), TaskType.PROFILE,
                outcome.success() ? RecordStatus.SUCCESS : RecordStatus.FAILED,
                outcome.path() == null ? null : outcome.path().toString(),
                outcome.success() ? null : outcome.error(), outcome.bytes(), null, null,
                outcome.success() ? Instant.now().toString() : null, DownloadRecord.DEFAULT_ATTEMPT));
    }
}
