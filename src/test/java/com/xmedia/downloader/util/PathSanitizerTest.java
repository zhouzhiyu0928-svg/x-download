package com.xmedia.downloader.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 护栏与清洗的回归测试。
 *
 * <p>真实事故回归：盘符冒号被误判（把整轮采集跑崩）与盘符被替换成 {@code G-}（路径写废）都出自这里，
 * 因此「保留盘符 / 盘符后无冒号 / 仍是绝对路径」是必测项；护栏分支需单独覆盖
 * （正常调用链里 {@link PathSanitizer#sanitize(String)} 已把冒号清掉，它永不触发）。
 */
class PathSanitizerTest {

    private static final String CRASH_PATH =
            "G:\\推特图片下载\\twitter_download\\save\\nao_mitsu23\\2026-10-12 19-24-img_0.jpg";

    @Test
    @DisplayName("崩溃现场路径：盘符保留、盘符后无冒号、仍是绝对路径")
    void crashPathIsSafe() {
        String out = PathSanitizer.guard(CRASH_PATH);
        assertEquals(CRASH_PATH, out);
        assertTrue(out.startsWith("G:\\"), out);
        assertFalse(PathSanitizer.stripPrefix(out).contains(":"), out);
        assertTrue(Paths.get(out).isAbsolute(), out);
    }

    @Test
    @DisplayName("文件名里的冒号被替换为连字符")
    void colonInNameIsReplaced() {
        String out = PathSanitizer.sanitize("G:/x/2026-10-11 16:24-img_1.jpg");
        assertEquals("G:\\x\\2026-10-11 16-24-img_1.jpg", out);
    }

    @Test
    @DisplayName("save_path 不会被改坏（盘符不被替换成 G-）")
    void drivePrefixSurvives() {
        String out = PathSanitizer.sanitize("G:/推特图片下载/twitter_download/save/2026-10-11 16:24-img_1.jpg");
        assertTrue(out.startsWith("G:\\推特图片下载\\twitter_download\\save\\"), out);
        assertFalse(out.startsWith("G-"), out);
    }

    @Test
    @DisplayName("其余非法字符一并清洗")
    void otherIllegalChars() {
        String out = PathSanitizer.sanitize("G:\\x\\a<b>c\"d|e?f*g\\h.jpg");
        assertEquals("G:\\x\\a-b-c-d-e-f-g\\h.jpg", out);
    }

    @Test
    @DisplayName("相对路径前缀 ./ 与 ../ 不得被改坏（曾把 ./downloads 清成 _\\downloads）")
    void dotSegmentsPreserved() {
        assertEquals(".\\downloads\\Susu0526m\\a.jpg",
                PathSanitizer.sanitize("./downloads/Susu0526m/a.jpg"));
        assertEquals("..\\downloads\\a.jpg", PathSanitizer.sanitize("../downloads/a.jpg"));
        assertEquals(".\\a.jpg", PathSanitizer.sanitize(".\\a.jpg"));
    }

    @Test
    @DisplayName("段尾空格与点被去掉，空段退化为下划线")
    void trailingDotsAndSpaces() {
        assertEquals("G:\\x\\name", PathSanitizer.sanitize("G:\\x\\name.  "));
        assertEquals("G:\\_", PathSanitizer.sanitize("G:\\   "));
    }

    @Test
    @DisplayName("Windows 保留名被加前缀规避")
    void reservedNames() {
        assertEquals("_CON.jpg", PathSanitizer.sanitize("CON.jpg"));
        assertEquals("_prn", PathSanitizer.sanitize("prn"));
        assertEquals("_LPT1.txt", PathSanitizer.sanitize("LPT1.txt"));
        assertEquals("CONSOLE.txt", PathSanitizer.sanitize("CONSOLE.txt"));
    }

    @Test
    @DisplayName("超长段被截断且保留扩展名")
    void longSegmentTruncated() {
        String name = "a".repeat(300) + ".jpg";
        String out = PathSanitizer.sanitizeSegment(name);
        assertTrue(out.length() <= PathSanitizer.MAX_SEGMENT_LENGTH, "len=" + out.length());
        assertTrue(out.endsWith(".jpg"), out);
    }

    @Test
    @DisplayName("护栏分支：绕过清洗时应拦住含冒号路径")
    void guardBranchFires() {
        // 正常链路上 sanitize 已清掉冒号 → 护栏不触发；这里直接测它那一支
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PathSanitizer.requireNoAds("G:\\x\\2026-10-11 16:24-img_1.jpg"));
        assertTrue(e.getMessage().contains("NTFS"), e.getMessage());
        // 反向对照：清洗后的路径必须通过
        assertEquals("G:\\x\\2026-10-11 16-24-img_1.jpg",
                PathSanitizer.requireNoAds("G:\\x\\2026-10-11 16-24-img_1.jpg"));
    }

    @Test
    @DisplayName("相对路径里像盘符的段（a:b）不得被当成盘符，冒号必须清洗")
    void driveLikeSegmentInRelativePath() {
        assertEquals("a-b\\2026-10-11 16-24-img_1.jpg",
                PathSanitizer.sanitize("a:b/2026-10-11 16:24-img_1.jpg"));
        // 反向对照：真正的盘符路径仍被识别为绝对路径
        assertTrue(Paths.get(PathSanitizer.sanitize("G:/x/y.jpg")).isAbsolute());
    }

    @Test
    @DisplayName("UNC 前缀与相对路径不被改坏")
    void uncAndRelative() {
        assertEquals("\\\\srv\\share\\x\\a-b.jpg", PathSanitizer.sanitize("\\\\srv\\share\\x\\a:b.jpg"));
        assertEquals("save\\2026-10-11 16-24-img_1.jpg",
                PathSanitizer.sanitize("save/2026-10-11 16:24-img_1.jpg"));
    }
}
