package com.xmedia.downloader.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xmedia.downloader.model.Target;
import com.xmedia.downloader.model.TargetStatus;
import com.xmedia.downloader.model.TaskResult;
import com.xmedia.downloader.storage.Database;
import com.xmedia.downloader.storage.TargetRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 按 ID 列表顺序批量下载：顺序、跳过无效/停用、单目标失败隔离。 */
class TargetListTaskTest {

    @TempDir
    Path dir;

    private Database db;
    private TargetRepository repo;

    @BeforeEach
    void setUp() {
        db = Database.open(dir.resolve("lt.db").toString());
        repo = new TargetRepository(db);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private static TaskResult result(TaskResult.Status status, int ok, int fail) {
        return new TaskResult("test", status, ok, fail, 0, 10L, List.of(), null, 2);
    }

    @Test
    @DisplayName("严格按列表顺序执行，且把顺序传给界面回调")
    void runsInListOrder() {
        repo.add("second", "");
        repo.add("first", "");
        repo.add("third", "");
        // 把 first 抬到最前
        repo.move(repo.findByName("first").id(), -1);

        List<String> called = new ArrayList<>();
        List<String> progress = new ArrayList<>();
        TargetListTask task = new TargetListTask(repo, (name, options) -> {
            called.add(name);
            assertEquals(5, options.limitTweets(), "参数应原样传给每个目标");
            return result(TaskResult.Status.SUCCESS, 3, 0);
        });

        TargetListTask.Summary summary = task.run(TargetListTask.Options.of(5, false, false), t -> progress.add(t.screenName()));

        assertEquals(List.of("first", "second", "third"), called);
        assertEquals(List.of("first", "second", "third"), progress);
        assertEquals(3, summary.success());
        assertEquals(9, summary.successFiles());
        assertEquals(0, summary.failed());
    }

    @Test
    @DisplayName("跳过无效与停用项，并计入 skipped")
    void skipsInvalidAndDisabled() {
        long valid = repo.add("keep", "").target().id();
        long invalid = repo.add("broken", "").target().id();
        long disabled = repo.add("paused", "").target().id();
        repo.updateCheck(TargetStatus.INVALID, "", "", 0, 0, "用户 @broken 不存在", invalid);
        repo.setEnabled(disabled, false);
        repo.updateCheck(TargetStatus.VALID, "1", "keep", 1, 1, "", valid);

        List<String> called = new ArrayList<>();
        TargetListTask task = new TargetListTask(repo, (name, o) -> {
            called.add(name);
            return result(TaskResult.Status.SUCCESS, 1, 0);
        });

        TargetListTask.Summary summary = task.run(TargetListTask.Options.of(0, false, false), null);

        assertEquals(List.of("keep"), called, "只有有效且启用的目标会被执行");
        assertEquals(2, summary.skipped());
        assertEquals(3, summary.total());
    }

    @Test
    @DisplayName("单个目标抛异常不影响后续目标（隔离），失败目标被记录")
    void failureIsolated() {
        repo.add("bad", "");
        repo.add("good", "");

        List<String> called = new ArrayList<>();
        TargetListTask task = new TargetListTask(repo, (name, o) -> {
            called.add(name);
            if (name.equals("bad")) {
                throw new IllegalStateException("模拟网络故障");
            }
            return result(TaskResult.Status.SUCCESS, 2, 0);
        });

        TargetListTask.Summary summary = task.run(TargetListTask.Options.of(0, false, false), null);

        assertEquals(List.of("bad", "good"), called, "失败目标之后的目标仍会执行");
        assertEquals(1, summary.failed());
        assertEquals(1, summary.success());
        assertTrue(summary.failedTargets().get(0).startsWith("bad"), summary.failedTargets().toString());
        assertTrue(summary.describe().contains("成功 1"));
    }

    @Test
    @DisplayName("空列表（或全部无效/停用）不执行任何目标")
    void emptyList() {
        List<String> called = new ArrayList<>();
        TargetListTask task = new TargetListTask(repo, (name, o) -> {
            called.add(name);
            return result(TaskResult.Status.SUCCESS, 1, 0);
        });

        TargetListTask.Summary summary = task.run(TargetListTask.Options.of(0, true, false), null);

        assertTrue(called.isEmpty());
        assertEquals(0, summary.total());
        assertEquals(0, summary.success());
    }
}
