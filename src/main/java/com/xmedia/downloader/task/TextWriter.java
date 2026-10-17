package com.xmedia.downloader.task;

import com.xmedia.downloader.model.Tweet;
import com.xmedia.downloader.util.PathSanitizer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 推文文本落盘（F7 的「同时保存文本」与 F9 纯文本任务共用）。 */
public final class TextWriter {

    private static final Logger log = LoggerFactory.getLogger(TextWriter.class);
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");
    private static final DateTimeFormatter HUMAN_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private TextWriter() {
    }

    /**
     * 写入单条推文文本，返回落盘路径（失败返回 null，不抛异常以不打断批量任务）。
     *
     * @param dir       目标目录（不存在会自动创建）
     * @param tweet     推文
     * @param alsoMedia 是否在文本里附带媒体直链
     */
    public static Path write(Path dir, Tweet tweet, boolean alsoMedia) {
        try {
            Files.createDirectories(dir);
            String stamp = tweet.createdAtMillis() == 0 ? "unknown"
                    : FILE_TIME.format(Instant.ofEpochMilli(tweet.createdAtMillis()).atZone(ZoneId.systemDefault()));
            String name = PathSanitizer.sanitizeSegment(stamp + "_" + tweet.tweetId() + ".txt");
            Path file = dir.resolve(name);
            StringBuilder sb = new StringBuilder();
            sb.append("推文ID: ").append(tweet.tweetId()).append('\n');
            sb.append("作者: @").append(tweet.userScreenName()).append('\n');
            if (tweet.createdAtMillis() > 0) {
                sb.append("时间: ").append(HUMAN_TIME.format(
                        Instant.ofEpochMilli(tweet.createdAtMillis()).atZone(ZoneId.systemDefault()))).append('\n');
            }
            sb.append("链接: https://twitter.com/").append(tweet.userScreenName())
                    .append("/status/").append(tweet.tweetId()).append('\n');
            if (tweet.retweet()) {
                sb.append("来源: 转推\n");
            }
            if (alsoMedia && !tweet.mediaItems().isEmpty()) {
                sb.append("媒体: ").append(tweet.mediaItems().size()).append(" 个\n");
                sb.append(tweet.mediaItems().stream().map(m -> "  - " + m.url())
                        .collect(Collectors.joining("\n"))).append('\n');
            }
            sb.append("---\n").append(tweet.text() == null ? "" : tweet.text()).append('\n');
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            log.warn("写文本失败：{} ← {}", tweet.tweetId(), e.getMessage());
            return null;
        }
    }
}
