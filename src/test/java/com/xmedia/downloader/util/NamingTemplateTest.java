package com.xmedia.downloader.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xmedia.downloader.model.MediaItem;
import com.xmedia.downloader.model.MediaType;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 命名模板：稳定键、变量校验、缺值失败、输出清洗。 */
class NamingTemplateTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long TS_2026_10_11 = 1791692640000L;   // 2026-10-11（本地时区）

    private static MediaItem item(String user, String tweetId, String mediaKey, String ext) {
        return new MediaItem(tweetId, mediaKey, user, MediaType.IMAGE,
                "https://pbs.twimg.com/media/" + mediaKey + "." + ext, ext, TS_2026_10_11, null);
    }

    private static NamingTemplate tpl(String t) {
        return new NamingTemplate(t, DateTimeFormatter.ofPattern("yyyyMMdd"), ZONE);
    }

    @Test
    @DisplayName("默认模板：用户名/日期_推文ID_媒体键.扩展名")
    void defaultTemplate() {
        String out = tpl(NamingTemplate.DEFAULT_TEMPLATE).render(item("AMU_WAY", "1234567", "G8Sovr7bcAAyeIO", "jpg"), 0);
        assertEquals("AMU_WAY\\20261011_1234567_G8Sovr7bcAAyeIO.jpg", out);
    }

    @Test
    @DisplayName("输出永不含冒号（重跑也不会写成 NTFS 数据流）")
    void outputNeverHasColon() {
        String out = tpl("{user}/{mediaKey}_{index}.{ext}").render(item("a:b", "1", "k:1", "jpg"), 7);
        assertFalse(PathSanitizer.stripPrefix(out).contains(":"), out);
        assertEquals("a-b\\k-1_7.jpg", out);
    }

    @Test
    @DisplayName("未知变量：启动即失败")
    void unknownVariableRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> NamingTemplate.validate("{user}/{unknownVar}.{ext}"));
        assertTrue(e.getMessage().contains("unknownVar"), e.getMessage());
    }

    @Test
    @DisplayName("空模板 / 无变量模板被拒")
    void emptyOrVariableLessRejected() {
        assertThrows(IllegalArgumentException.class, () -> NamingTemplate.validate(""));
        assertThrows(IllegalArgumentException.class, () -> NamingTemplate.validate("static/name.jpg"));
    }

    @Test
    @DisplayName("变量无值（如 likes 场景缺 user）→ 明确异常，不静默产出怪路径")
    void missingValueFails() {
        MediaItem blankUser = item("", "1", "key", "jpg");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> tpl(NamingTemplate.DEFAULT_TEMPLATE).render(blankUser, 0));
        assertTrue(e.getMessage().contains("{user}"), e.getMessage());
    }

    @Test
    @DisplayName("{index} 可用，且 extra 可覆盖内置变量")
    void indexAndExtra() {
        String out = tpl("{user}/{index}.{ext}").render(item("u", "1", "k", "png"), 3, Map.of("ext", "jpg"));
        assertEquals("u\\3.jpg", out);
    }

    @Test
    @DisplayName("视频变体与 gif 类型可渲染")
    void videoAndGif() {
        MediaItem video = new MediaItem("9", "vidkey", "u", MediaType.VIDEO, "https://video.twimg.com/x.mp4",
                "mp4", TS_2026_10_11, 832000);
        assertEquals("u\\20261011_9_vidkey.mp4", tpl(NamingTemplate.DEFAULT_TEMPLATE).render(video, 0));
        MediaItem gif = new MediaItem("9", "gifkey", "u", MediaType.GIF, "https://video.twimg.com/x.mp4",
                "mp4", TS_2026_10_11, null);
        assertTrue(tpl("{type}/{tweetId}.{ext}").render(gif, 0).startsWith("gif\\"), "gif 段应来自 {type}");
    }
}
