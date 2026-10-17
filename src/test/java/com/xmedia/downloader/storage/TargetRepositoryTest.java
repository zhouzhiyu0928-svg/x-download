package com.xmedia.downloader.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xmedia.downloader.model.Target;
import com.xmedia.downloader.model.TargetStatus;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ID 列表（可增加 / 可删除 / 可校验 / 按顺序下载）的存取与顺序语义。 */
class TargetRepositoryTest {

    @TempDir
    Path dir;

    private Database db;
    private TargetRepository repo;

    @BeforeEach
    void setUp() {
        db = Database.open(dir.resolve("t.db").toString());
        repo = new TargetRepository(db);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    @DisplayName("归一化：@前缀 / 链接 / 前后空白都接受；非法输入返回 null")
    void normalize() {
        assertEquals("Susu0526m", TargetRepository.normalize("  @Susu0526m "));
        assertEquals("Susu0526m", TargetRepository.normalize("https://x.com/Susu0526m"));
        assertEquals("Susu0526m", TargetRepository.normalize("x.com/Susu0526m/status/1"));
        assertEquals("Susu0526m", TargetRepository.normalize("twitter.com/Susu0526m"));
        assertEquals("a_b123", TargetRepository.normalize("a_b123"));
        assertNull(TargetRepository.normalize(""));
        assertNull(TargetRepository.normalize("   "));
        assertNull(TargetRepository.normalize("has space"), "中间空格不是合法 ID");
        assertNull(TargetRepository.normalize("this_name_is_way_too_long_for_x"));
        assertNull(TargetRepository.normalize("中文名字"));
    }

    @Test
    @DisplayName("新增：写入顺序递增；重复（忽略大小写）不重复插入")
    void addAndDedupe() {
        assertEquals(1, repo.add("aaa", "第一个").target().sortOrder());
        assertEquals(2, repo.add("bbb", "").target().sortOrder());
        assertEquals(3, repo.add("ccc", "").target().sortOrder());

        TargetRepository.AddOutcome again = repo.add("AAA", "");
        assertFalse(again.created(), "大小写不同视为同一个 ID");
        assertEquals(3, repo.count());
        assertEquals(TargetStatus.UNKNOWN, again.target().status());
        assertEquals("第一个", again.target().note());
    }

    @Test
    @DisplayName("已存在的 ID 再带新备注加入 → 更新备注而不是白写")
    void addExistingUpdatesNote() {
        repo.add("dup", "旧备注");
        assertEquals("旧备注", repo.findByName("dup").note());

        TargetRepository.AddOutcome again = repo.add("dup", "新备注");
        assertFalse(again.created());
        assertEquals("新备注", repo.findByName("dup").note(), "重复加入时应更新备注");
        assertEquals(1, repo.count());

        // 不带备注时不覆盖已有备注
        repo.add("dup", "");
        assertEquals("新备注", repo.findByName("dup").note());
    }

    @Test
    @DisplayName("顺序：list 按 sort_order；move 与相邻交换；端点不动")
    void ordering() {
        repo.add("a", "");
        repo.add("b", "");
        repo.add("c", "");

        List<Target> before = repo.list();
        assertEquals(List.of("a", "b", "c"), before.stream().map(Target::screenName).toList());

        Target b = repo.findByName("b");
        assertTrue(repo.move(b.id(), -1));
        assertEquals(List.of("b", "a", "c"), repo.list().stream().map(Target::screenName).toList());

        assertFalse(repo.move(b.id(), -1), "已在首位，不能再上移");
        assertTrue(repo.move(b.id(), 1));
        assertEquals(List.of("a", "b", "c"), repo.list().stream().map(Target::screenName).toList());
        assertFalse(repo.move(repo.findByName("c").id(), 1), "已在末位，不能再下移");
    }

    @Test
    @DisplayName("校验结果回写；无效项进入 invalid()；一键删除只删无效")
    void checkResults() {
        long okId = repo.add("good", "").target().id();
        long badId = repo.add("bad", "").target().id();

        repo.updateCheck(TargetStatus.VALID, "111", "好账号", 12, 30, "", okId);
        repo.updateCheck(TargetStatus.INVALID, "", "", 0, 0, "用户 @bad 不存在", badId);

        Target good = repo.find(okId);
        assertEquals(TargetStatus.VALID, good.status());
        assertEquals("好账号", good.displayName());
        assertEquals(12, good.mediaCount());
        assertEquals(30, good.tweetCount());
        assertNotNull(good.lastCheckedAt());

        assertEquals(1, repo.invalidCount());
        assertEquals(List.of("bad"), repo.invalid().stream().map(Target::screenName).toList());
        assertEquals(1, repo.runnable().size(), "无效项不参与批量下载");

        assertEquals(1, repo.removeInvalid());
        assertEquals(1, repo.count());
        assertEquals("good", repo.list().get(0).screenName());
    }

    @Test
    @DisplayName("停用：runnable 跳过停用项（保留条目）")
    void disabledNotRunnable() {
        long id = repo.add("idle", "").target().id();
        repo.add("busy", "");
        assertEquals(2, repo.runnable().size());

        repo.setEnabled(id, false);
        assertEquals(1, repo.runnable().size());
        assertEquals("busy", repo.runnable().get(0).screenName());
        assertEquals(2, repo.count(), "停用只是不参与下载，条目仍保留");
    }

    @Test
    @DisplayName("删除：按 id 删除；不存在时返回 0")
    void remove() {
        long id = repo.add("todelete", "").target().id();
        assertEquals(1, repo.remove(id));
        assertEquals(0, repo.remove(id));
        assertEquals(0, repo.count());
    }

    @Test
    @DisplayName("reorder：按给定 id 顺序重排，未列出的排后面")
    void reorder() {
        long a = repo.add("a", "").target().id();
        long b = repo.add("b", "").target().id();
        long c = repo.add("c", "").target().id();

        repo.reorder(List.of(c, a));
        assertEquals(List.of("c", "a", "b"), repo.list().stream().map(Target::screenName).toList());
    }
}
