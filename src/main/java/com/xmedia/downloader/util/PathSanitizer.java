package com.xmedia.downloader.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Windows 文件名/路径清洗与护栏。
 *
 * <p>背景（真实事故）：文件名里的冒号会被 NTFS 解析为「数据流分隔符」——
 * {@code G:\...\2026-10-11 16:24-img_1.jpg} 实际落盘为 0 字节的可见文件
 * {@code 2026-10-11 16} + 隐藏流 {@code :24-img_1.jpg}。写盘不报错、程序报告成功，
 * 数据在资源管理器里完全不可见（详见同目录设计文档与技能 windows-ads-recovery）。
 *
 * <p>两条硬约束：① 盘符（{@code G:}）与 UNC 前缀必须先剥离，否则会被一起替换掉；
 * ② 护栏（{@link #requireNoAds(String)}）判冒号前同样必须先剥盘符。
 */
public final class PathSanitizer {

    private static final Set<Character> ILLEGAL = Set.of('<', '>', ':', '"', '/', '\\', '|', '?', '*');
    private static final Set<String> RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");
    /** 单个路径段的最大长度（Windows 全路径 260 限制下留足余量给目录与扩展名）。 */
    public static final int MAX_SEGMENT_LENGTH = 120;

    /** 盘符：必须紧跟分隔符才算（否则相对路径 "a:b\c" 里的 "a:" 会被误当盘符，段内冒号漏清洗）。 */
    private static final Pattern DRIVE = Pattern.compile("^[A-Za-z]:(?=[\\\\/])");
    private static final Pattern UNC = Pattern.compile("^(\\\\\\\\[^\\\\/]+\\\\[^\\\\/]+)");

    private PathSanitizer() {
    }

    /** 清洗整条路径：替换非法字符、去段尾空格与点、规避保留名、限长；盘符/UNC 前缀原样保留。 */
    public static String sanitize(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        String prefix = "";
        String rest = path;
        Matcher drive = DRIVE.matcher(path);
        Matcher unc = UNC.matcher(path);
        if (drive.find()) {
            prefix = path.substring(0, drive.end());
            rest = path.substring(drive.end());
        } else if (unc.find()) {
            prefix = unc.group(1);
            rest = path.substring(unc.end());
        }

        boolean leadingSeparator = !rest.isEmpty() && (rest.charAt(0) == '/' || rest.charAt(0) == '\\');
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '\\') {
                if (current.length() > 0) {
                    segments.add(cleanSegment(current.toString()));
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            segments.add(cleanSegment(current.toString()));
        }

        String joined = String.join("\\", segments);
        if (leadingSeparator && joined.isEmpty()) {
            return prefix + "\\";
        }
        return prefix + (leadingSeparator ? "\\" : "") + joined;
    }

    /**
     * 输出路径护栏：先清洗，再断言「盘符之后」不含冒号。返回可直接用于写盘的路径。
     *
     * @throws IllegalArgumentException 盘符之后仍含冒号（正常情况下不会发生，属最后一道保险）
     */
    public static String guard(String path) {
        return requireNoAds(sanitize(path));
    }

    /**
     * 断言（已清洗的）路径在盘符之后不含冒号。包级可见以便单独测试该分支：
     * {@link #sanitize(String)} 已把冒号替换掉，正常调用链中此分支不会触发。
     */
    static String requireNoAds(String sanitizedPath) {
        String rest = stripPrefix(sanitizedPath);
        if (rest.indexOf(':') >= 0) {
            throw new IllegalArgumentException(
                    "输出路径（盘符之后）含冒号，会被写成 NTFS 数据流：" + sanitizedPath);
        }
        return sanitizedPath;
    }

    /** 去掉盘符 / UNC 前缀，剩下的相对部分用于安全检查。 */
    public static String stripPrefix(String path) {
        if (path == null) {
            return "";
        }
        Matcher drive = DRIVE.matcher(path);
        if (drive.find()) {
            return path.substring(drive.end());
        }
        Matcher unc = UNC.matcher(path);
        if (unc.find()) {
            return path.substring(unc.end());
        }
        return path;
    }

    /** 仅清洗单个路径段（不含分隔符）。 */
    public static String sanitizeSegment(String segment) {
        return cleanSegment(segment);
    }

    private static String cleanSegment(String segment) {
        // "." 与 ".." 是合法路径成分，不能当成「尾巴是点」被清掉或退化成 "_"
        // （否则 "./downloads" 会变成 "_\downloads"，2026-10-16 实测踩到）
        if (segment.equals(".") || segment.equals("..")) {
            return segment;
        }
        StringBuilder out = new StringBuilder(segment.length());
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            out.append(ILLEGAL.contains(c) ? '-' : c);
        }
        String s = out.toString();
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == ' ' || s.charAt(end - 1) == '.')) {
            end--;
        }
        s = s.substring(0, end);
        if (s.isEmpty()) {
            return "_";
        }
        String base = s;
        int dot = base.indexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        if (RESERVED.contains(base.toUpperCase(Locale.ROOT))) {
            s = "_" + s;
        }
        if (s.length() > MAX_SEGMENT_LENGTH) {
            int d = s.lastIndexOf('.');
            String ext = d > 0 && s.length() - d <= 12 ? s.substring(d) : "";
            s = s.substring(0, Math.max(1, MAX_SEGMENT_LENGTH - ext.length())) + ext;
        }
        return s;
    }
}
