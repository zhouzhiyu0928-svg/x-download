package com.xmedia.downloader.task;

import com.xmedia.downloader.model.Target;
import com.xmedia.downloader.model.TaskResult;
import com.xmedia.downloader.storage.TargetRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按 ID 列表顺序批量下载：逐个取列表条目（跳过无效/停用）、按 {@code sort_order} 顺序执行。
 *
 * <p>每个条目的实际执行由外部注入的 {@code perTarget} 完成（生产环境是 {@code UserMediaTask}），
 * 因此本类的顺序/跳过/汇总逻辑可独立单测，不必起网络。
 */
public final class TargetListTask {

    private static final Logger log = LoggerFactory.getLogger(TargetListTask.class);

    /** 单个目标的执行参数（与 AbstractTimelineTask.Options 对齐，便于外部注入）。 */
    public record Options(int limitTweets, boolean dryRun, boolean fresh) {

        public static Options of(int limitTweets, boolean dryRun, boolean fresh) {
            return new Options(limitTweets, dryRun, fresh);
        }
    }

    /** 单个目标的执行器（生产环境是 UserMediaTask；测试可注入假实现）。 */
    public interface PerTarget {
        TaskResult run(String screenName, Options options);
    }

    private final TargetRepository targets;
    private final PerTarget perTarget;

    public TargetListTask(TargetRepository targets, PerTarget perTarget) {
        this.targets = targets;
        this.perTarget = perTarget;
    }

    public record Summary(int total, int success, int failed, int skipped, List<String> failedTargets,
                          int successFiles, int failedFiles, int apiCalls) {

        public String describe() {
            return String.format("列表任务完成：%d 个目标（成功 %d / 失败 %d / 跳过 %d），媒体成功 %d / 失败 %d",
                    total, success, failed, skipped, successFiles, failedFiles);
        }
    }

    /**
     * 按顺序执行。
     *
     * @param progress 每个目标开始前的回调（界面用来显示「正在处理 3/12 @xxx」）
     */
    public Summary run(Options options, Consumer<Target> progress) {
        List<Target> runnable = targets.runnable();
        int all = (int) targets.count();
        int skipped = all - runnable.size();
        int success = 0;
        int failed = 0;
        int files = 0;
        int fileFailures = 0;
        int apiCalls = 0;
        List<String> failedTargets = new ArrayList<>();

        if (runnable.isEmpty()) {
            log.warn("ID 列表里没有可下载的目标（{} 条被停用或判为无效）", skipped);
            return new Summary(all, 0, 0, skipped, List.of(), 0, 0, 0);
        }
        log.info("按列表顺序开始下载：共 {} 个目标（另有 {} 条跳过）", runnable.size(), skipped);
        for (Target target : runnable) {
            if (progress != null) {
                progress.accept(target);
            }
            log.info("—— 列表顺序下载：{} ——", target.label());
            try {
                TaskResult result = perTarget.run(target.screenName(), options);
                if (result == null) {
                    failed++;
                    failedTargets.add(target.screenName() + "（无结果）");
                    continue;
                }
                if (result.status() == TaskResult.Status.SUCCESS || result.status() == TaskResult.Status.PARTIAL) {
                    success++;
                } else {
                    failed++;
                    failedTargets.add(target.screenName() + "（" + result.status() + "）");
                }
                files += result.successCount();
                fileFailures += result.failedCount();
                apiCalls += result.apiCallCount();
            } catch (RuntimeException e) {
                // 单个目标失败不影响后续目标（例如某账号配额/限流，下一个继续）
                failed++;
                failedTargets.add(target.screenName() + "（" + e.getClass().getSimpleName() + "）");
                log.warn("目标 @{} 执行失败：{}", target.screenName(), e.getMessage());
            }
        }
        Summary summary = new Summary(all, success, failed, skipped, failedTargets, files, fileFailures, apiCalls);
        log.info(summary.describe());
        return summary;
    }
}
