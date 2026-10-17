package com.xmedia.downloader.util;

import com.xmedia.downloader.model.MediaItem;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 命名模板渲染：把 {@code {user}/{date}_{tweetId}_{mediaKey}.{ext}} 渲染为落盘相对路径。
 *
 * <p>可用变量：{@code {user} {date} {tweetId} {mediaKey} {type} {index} {ext}}。
 * 默认模板只用稳定键（tweetId / mediaKey）：{@code {index}} 仅表示「本轮运行内序号」，重跑会变，
 * 持久命名请勿使用（设计文档 §4.1.1）。所有输出都经 {@link PathSanitizer} 清洗。
 */
public final class NamingTemplate {

    /** 默认模板：用户名目录 + 日期_推文ID_媒体键.扩展名（稳定键，重跑不冲突）。 */
    public static final String DEFAULT_TEMPLATE = "{user}/{date}_{tweetId}_{mediaKey}.{ext}";

    private static final Pattern VAR = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");
    private static final Set<String> KNOWN = new LinkedHashSet<>(
            java.util.List.of("user", "date", "tweetId", "mediaKey", "type", "index", "ext"));

    private final String template;
    private final DateTimeFormatter dateFormat;
    private final ZoneId zone;

    public NamingTemplate(String template) {
        this(template, DateTimeFormatter.ofPattern("yyyyMMdd"), ZoneId.systemDefault());
    }

    public NamingTemplate(String template, DateTimeFormatter dateFormat, ZoneId zone) {
        validate(template);
        this.template = template;
        this.dateFormat = dateFormat;
        this.zone = zone;
    }

    public static NamingTemplate defaultTemplate() {
        return new NamingTemplate(DEFAULT_TEMPLATE);
    }

    /** 校验模板：非空、含至少一个变量、不含未知变量。启动阶段失败优于运行时静默出错。 */
    public static void validate(String template) {
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException("命名模板不能为空");
        }
        Matcher m = VAR.matcher(template);
        int count = 0;
        while (m.find()) {
            count++;
            String name = m.group(1);
            if (!KNOWN.contains(name)) {
                throw new IllegalArgumentException(
                        "命名模板含未知变量：{" + name + "}，可用变量：" + KNOWN);
            }
        }
        if (count == 0) {
            throw new IllegalArgumentException("命名模板不含任何变量：" + template);
        }
    }

    /**
     * 渲染相对路径。
     *
     * @param item  媒体项
     * @param index 本轮运行内序号（仅供 {index}）
     * @param extra 额外变量（覆盖同名内置变量）
     */
    public String render(MediaItem item, int index, Map<String, String> extra) {
        Map<String, String> vars = new HashMap<>();
        vars.put("user", item.userScreenName());
        vars.put("date", dateFormat.format(Instant.ofEpochMilli(item.tweetCreatedAtMillis()).atZone(zone)));
        vars.put("tweetId", item.tweetId());
        vars.put("mediaKey", item.mediaKey());
        vars.put("type", item.type().label());
        vars.put("index", Integer.toString(index));
        vars.put("ext", item.extension());
        if (extra != null) {
            vars.putAll(extra);
        }

        StringBuilder out = new StringBuilder();
        Matcher m = VAR.matcher(template);
        int last = 0;
        while (m.find()) {
            out.append(template, last, m.start());
            String name = m.group(1);
            String value = vars.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(
                        "命名模板变量 {" + name + "} 无值（tweet " + item.tweetId() + " / " + item.mediaKey() + "）");
            }
            out.append(value);
            last = m.end();
        }
        out.append(template.substring(last));
        return PathSanitizer.guard(out.toString());
    }

    public String render(MediaItem item, int index) {
        return render(item, index, null);
    }
}
