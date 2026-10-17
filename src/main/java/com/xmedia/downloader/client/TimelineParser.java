package com.xmedia.downloader.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.xmedia.downloader.model.MediaItem;
import com.xmedia.downloader.model.MediaType;
import com.xmedia.downloader.model.Tweet;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 时间线响应解析（UserMedia / UserTweets 通用）。
 *
 * <p>与原 Python 版的显式路径取值不同，这里对每个 entry 做**递归查找** {@code tweet_results}
 * （原版注释就写过「此模式 API 返回值变动」，硬编码路径易随版本失效）；cursor 与
 * moduleItems / items 包装差异都由同一条递归路径消化。
 */
public final class TimelineParser {

    private static final DateTimeFormatter X_TIME =
            DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH);

    /**
     * @param nextCursor 空表示已到底
     * @param topReached 命中 cursor-top（转推模式表示全部取完）
     * @param raw        原始响应（--dump-pages 落盘用），可为 null
     */
    public record Page(List<Tweet> tweets, String nextCursor, boolean topReached, JsonNode raw) {

        public Page(List<Tweet> tweets, String nextCursor, boolean topReached) {
            this(tweets, nextCursor, topReached, null);
        }
    }

    private final boolean hasVideo;
    private final boolean includeRetweet;

    public TimelineParser(boolean hasVideo, boolean includeRetweet) {
        this.hasVideo = hasVideo;
        this.includeRetweet = includeRetweet;
    }

    public Page parse(JsonNode root) {
        // 通用定位：UserMedia/UserTweets 在 data.user.result.timeline_v2，
        // SearchTimeline 在 data.search_by_raw_query.search_timeline，
        // TweetDetail 在 data.threaded_conversation_with_injections_v2 —— 一律靠递归找 instructions
        List<JsonNode> instructionLists = new ArrayList<>();
        collectInstructionArrays(root, instructionLists);
        List<Tweet> tweets = new ArrayList<>();
        String cursor = null;
        boolean topReached = false;

        for (JsonNode instructions : instructionLists) {
        for (JsonNode instruction : instructions) {
            JsonNode entries = instruction.path("entries");
            if (!entries.isArray()) {
                continue;
            }
            for (JsonNode entry : entries) {
                String entryId = entry.path("entryId").asText("");
                if (entryId.startsWith("cursor-bottom")) {
                    String value = entry.path("content").path("value").asText("");
                    if (!value.isBlank()) {
                        cursor = value;
                    }
                }
                if (entryId.startsWith("cursor-top")) {
                    topReached = true;
                }
                List<JsonNode> results = new ArrayList<>();
                collectTweetResults(entry, results);
                for (JsonNode r : results) {
                    Tweet t = mapTweet(r);
                    if (t != null) {
                        tweets.add(t);
                    }
                }
            }
        }
        }
        return new Page(dedupe(tweets), cursor, topReached, root);
    }

    /** 递归找所有 instructions 数组（找到即收，不再深入）。 */
    private void collectInstructionArrays(JsonNode node, List<JsonNode> out) {
        if (node.isObject()) {
            JsonNode instructions = node.get("instructions");
            if (instructions != null && instructions.isArray()) {
                out.add(instructions);
                return;
            }
            node.fields().forEachRemaining(e -> collectInstructionArrays(e.getValue(), out));
        } else if (node.isArray()) {
            node.forEach(child -> collectInstructionArrays(child, out));
        }
    }

    /** 递归查找所有 {@code tweet_results.result}（含 retweeted_status_result 交给 mapTweet 处理）。 */
    private void collectTweetResults(JsonNode node, List<JsonNode> out) {
        if (node.isObject()) {
            JsonNode tr = node.get("tweet_results");
            if (tr != null && tr.has("result")) {
                out.add(tr.get("result"));
                return;   // 命中后不再深入，避免重复/把引用推文当独立推文
            }
            node.fields().forEachRemaining(e -> collectTweetResults(e.getValue(), out));
        } else if (node.isArray()) {
            node.forEach(child -> collectTweetResults(child, out));
        }
    }

    private Tweet mapTweet(JsonNode result) {
        JsonNode node = result;
        if (node.path("__typename").asText("").equals("TweetWithVisibilityResults") && node.has("tweet")) {
            node = node.path("tweet");
        }
        JsonNode legacy = node.path("legacy");
        if (legacy.isMissingNode() || legacy.isEmpty()) {
            return null;
        }
        boolean retweet = legacy.has("retweeted_status_result");
        if (retweet && !includeRetweet) {
            return null;
        }
        if (retweet) {
            JsonNode nested = legacy.path("retweeted_status_result").path("result");
            Tweet mapped = mapTweet(nested);
            if (mapped != null) {
                // 转推：媒体与作者归属原推文，但标记 retweet=true 便于上层识别来源
                return new Tweet(mapped.tweetId(), mapped.text(), mapped.createdAtMillis(),
                        mapped.userRestId(), mapped.userScreenName(), true, mapped.mediaItems());
            }
        }

        String tweetId = legacy.path("id_str").asText(node.path("rest_id").asText(""));
        if (tweetId.isBlank()) {
            return null;
        }
        // 作者名有三种落点：新版搜索/时间线用 core.user_results.result.core.screen_name，
        // 旧版用 ...result.legacy.screen_name，最老的在 legacy.user.screen_name（搜索结果曾因此变成空作者）
        JsonNode userResult = node.path("core").path("user_results").path("result");
        String screenName = userResult.path("core").path("screen_name").asText(
                userResult.path("legacy").path("screen_name").asText(
                        legacy.path("user").path("screen_name").asText("")));
        String userRestId = userResult.path("rest_id")
                .asText(legacy.path("user").path("id_str").asText(""));
        long createdAt = parseCreatedAt(legacy.path("created_at").asText(""));

        List<MediaItem> media = extractMedia(tweetId, screenName, createdAt, legacy);
        if (media.isEmpty()) {
            return null;
        }
        return new Tweet(tweetId, legacy.path("full_text").asText(""), createdAt,
                userRestId, screenName, retweet, media);
    }

    private List<MediaItem> extractMedia(String tweetId, String screenName, long createdAt, JsonNode legacy) {
        JsonNode media = legacy.path("extended_entities").path("media");
        if (!media.isArray()) {
            media = legacy.path("entities").path("media");
        }
        List<MediaItem> out = new ArrayList<>();
        if (!media.isArray()) {
            return out;
        }
        for (JsonNode m : media) {
            String type = m.path("type").asText("photo");
            String mediaKey = mediaKeyOf(m);
            if (type.equalsIgnoreCase("photo")) {
                String url = m.path("media_url_https").asText(m.path("media_url").asText(""));
                if (url.isBlank()) {
                    continue;
                }
                out.add(new MediaItem(tweetId, mediaKey, screenName, MediaType.IMAGE, url,
                        photoExtension(url), createdAt, null));
            } else {
                if (!hasVideo) {
                    continue;
                }
                JsonNode variants = m.path("video_info").path("variants");
                String url = highestBitrateUrl(variants);
                if (url == null || url.isBlank()) {
                    continue;
                }
                Integer bitrate = highestBitrate(variants);
                MediaType mt = type.equalsIgnoreCase("animated_gif") ? MediaType.GIF : MediaType.VIDEO;
                out.add(new MediaItem(tweetId, mediaKey, screenName, mt, url, "mp4", createdAt, bitrate));
            }
        }
        return out;
    }

    /** media_key 缺失时用 URL 末段（文件名）兜底 —— 与原版用 URL 名做文件名一致，且同样稳定。 */
    public static String mediaKeyOf(JsonNode media) {
        String key = media.path("media_key").asText("");
        if (!key.isBlank()) {
            return key;
        }
        String url = media.path("media_url_https").asText("");
        if (url.isBlank() && media.path("video_info").path("variants").isArray()) {
            url = media.path("video_info").path("variants").path(0).path("url").asText("");
        }
        String path = url.split("\\?")[0];
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** 图片后缀从 URL 的 path 段取（不要用 url 末尾切片：会取到 '&name=small' 的 'all'）。 */
    public static String photoExtension(String url) {
        String path = url.split("\\?")[0];
        int dot = path.lastIndexOf('.');
        if (dot < 0) {
            return "jpg";
        }
        String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "jpg", "jpeg", "png", "webp", "gif" -> ext.equals("jpeg") ? "jpg" : ext;
            default -> "jpg";
        };
    }

    /** 视频变体：单变体是 gif；否则取 bitrate 最高者（与原版一致）。 */
    public static String highestBitrateUrl(JsonNode variants) {
        if (variants == null || !variants.isArray() || variants.isEmpty()) {
            return null;
        }
        if (variants.size() == 1) {
            return variants.path(0).path("url").asText(null);
        }
        String best = null;
        long bestBitrate = -1;
        for (JsonNode v : variants) {
            if (v.has("bitrate") && v.path("bitrate").asLong(0) > bestBitrate) {
                bestBitrate = v.path("bitrate").asLong(0);
                best = v.path("url").asText(null);
            }
        }
        return best == null ? variants.path(variants.size() - 1).path("url").asText(null) : best;
    }

    public static Integer highestBitrate(JsonNode variants) {
        if (variants == null || !variants.isArray()) {
            return null;
        }
        int best = -1;
        for (JsonNode v : variants) {
            if (v.has("bitrate")) {
                best = Math.max(best, v.path("bitrate").asInt(0));
            }
        }
        return best < 0 ? null : best;
    }

    public static long parseCreatedAt(String text) {
        if (text == null || text.isBlank()) {
            return 0L;
        }
        try {
            return LocalDateTime.parse(text, X_TIME).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static List<Tweet> dedupe(List<Tweet> tweets) {
        Map<String, Tweet> byId = new LinkedHashMap<>();
        for (Tweet t : tweets) {
            byId.putIfAbsent(t.tweetId(), t);
        }
        return List.copyOf(byId.values());
    }
}
