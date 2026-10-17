package com.xmedia.downloader.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xmedia.downloader.model.MediaItem;
import com.xmedia.downloader.model.MediaType;
import com.xmedia.downloader.model.Tweet;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 时间线解析：主线用**线上真实响应夹具**；分支用 Jackson ObjectNode 构造的合成样例
 * （不用手写 JSON，避免括号/转义出错）。
 */
class TimelineParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CREATED_AT = "Thu Oct 15 14:35:54 +0000 2026";

    private JsonNode fixture() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/usermedia-page-02.json")) {
            assertNotNull(in, "缺少测试夹具 fixtures/usermedia-page-02.json");
            return MAPPER.readTree(in);
        }
    }

    // ------------------------------------------------------------ 真实夹具
    @Test
    @DisplayName("真实响应：4 条推文共 15 张图，id/时间/媒体键均正确")
    void realResponseFixture() throws Exception {
        TimelineParser.Page page = new TimelineParser(true, false).parse(fixture());

        assertEquals(List.of("2110741460089983155", "2110601926371061997",
                        "2109885604024746369", "2104596547417911500"),
                page.tweets().stream().map(Tweet::tweetId).toList());
        assertEquals(List.of(4, 4, 4, 3),
                page.tweets().stream().map(t -> t.mediaItems().size()).toList());

        Tweet first = page.tweets().get(0);
        assertEquals("Susu0526m", first.userScreenName());
        assertEquals("1650579264494874624", first.userRestId());
        assertEquals(Instant.parse("2026-10-15T14:35:54Z").toEpochMilli(), first.createdAtMillis());
        assertEquals(List.of("3_2110741447612006400", "3_2110741447553261568",
                        "3_2110741447586750464", "3_2110741447540686848"),
                first.mediaItems().stream().map(MediaItem::mediaKey).toList());
        assertTrue(first.mediaItems().stream().allMatch(m -> m.type() == MediaType.IMAGE));
        assertTrue(first.mediaItems().stream().allMatch(m -> m.extension().equals("jpg")));
        assertTrue(first.mediaItems().stream().allMatch(m -> m.url().startsWith("https://pbs.twimg.com/media/")));
        assertTrue(page.nextCursor() != null && !page.nextCursor().isBlank(), "应给出下一页游标");
    }

    // ------------------------------------------------------------ 视频 / gif
    @Test
    @DisplayName("视频：多变体取最高码率；hasVideo=false 时同一响应不产出媒体项")
    void videoVariants() throws Exception {
        ArrayNode variants = MAPPER.createArrayNode();
        variants.add(MAPPER.createObjectNode().put("bitrate", 256000).put("url", "https://video.twimg.com/low.mp4"));
        variants.add(MAPPER.createObjectNode().put("bitrate", 2176000).put("url", "https://video.twimg.com/high.mp4"));
        variants.add(MAPPER.createObjectNode().put("content_type", "application/x-mpegURL")
                .put("url", "https://video.twimg.com/playlist.m3u8"));

        assertEquals("https://video.twimg.com/high.mp4", TimelineParser.highestBitrateUrl(variants));
        assertEquals(2176000, TimelineParser.highestBitrate(variants));

        JsonNode page = pageWithVideo("video", variants);
        List<MediaItem> items = new TimelineParser(true, false).parse(page).tweets().get(0).mediaItems();
        assertEquals(1, items.size());
        assertEquals(MediaType.VIDEO, items.get(0).type());
        assertEquals("mp4", items.get(0).extension());
        assertEquals(2176000, items.get(0).variantBitrate());

        assertTrue(new TimelineParser(false, false).parse(page).tweets().isEmpty(),
                "关闭视频后不得产出媒体项");
    }

    @Test
    @DisplayName("gif：单变体直取（animated_gif → GIF + mp4，无码率）")
    void gifHandling() throws Exception {
        ArrayNode variants = MAPPER.createArrayNode();
        variants.add(MAPPER.createObjectNode().put("url", "https://video.twimg.com/tweet_video/x.mp4"));
        assertEquals("https://video.twimg.com/tweet_video/x.mp4", TimelineParser.highestBitrateUrl(variants));

        List<MediaItem> items = new TimelineParser(true, false)
                .parse(pageWithVideo("animated_gif", variants)).tweets().get(0).mediaItems();
        assertEquals(MediaType.GIF, items.get(0).type());
        assertEquals("mp4", items.get(0).extension());
        assertNull(items.get(0).variantBitrate());
    }

    // ------------------------------------------------------------ 转推
    @Test
    @DisplayName("转推：默认丢弃；开启后归属原作者与原推文 ID")
    void retweetHandling() throws Exception {
        JsonNode page = pageWithEntry(retweetEntry());

        assertTrue(new TimelineParser(true, false).parse(page).tweets().isEmpty(), "默认不处理转推");

        List<Tweet> kept = new TimelineParser(true, true).parse(page).tweets();
        assertEquals(1, kept.size());
        assertEquals("111", kept.get(0).tweetId(), "转推应归属原推文 ID");
        assertEquals("orig_author", kept.get(0).userScreenName(), "媒体应归属原作者");
        assertTrue(kept.get(0).retweet());
        assertEquals(1, kept.get(0).mediaItems().size());
    }

    @Test
    @DisplayName("搜索 / 评论区响应形状：instructions 位于不同路径也能定位")
    void searchAndThreadShapes() throws Exception {
        ArrayNode entries = MAPPER.createArrayNode();
        entries.add(tweetEntry(photoMedia()));
        entries.add(cursorEntry("cursor-bottom-9", "CURSOR_NEXT"));
        ObjectNode instruction = MAPPER.createObjectNode();
        instruction.put("type", "TimelineAddEntries");
        instruction.set("entries", entries);
        ArrayNode instructions = MAPPER.createArrayNode();
        instructions.add(instruction);

        // SearchTimeline：data.search_by_raw_query.search_timeline.timeline.instructions
        ObjectNode searchTimeline = MAPPER.createObjectNode();
        searchTimeline.set("instructions", instructions);
        ObjectNode searchRoot = MAPPER.createObjectNode();
        searchRoot.set("search_timeline", searchTimeline);
        ObjectNode searchData = MAPPER.createObjectNode();
        searchData.set("search_by_raw_query", searchRoot);
        JsonNode searchPage = MAPPER.createObjectNode().set("data", searchData);
        TimelineParser.Page fromSearch = new TimelineParser(true, false).parse(searchPage);
        assertEquals(1, fromSearch.tweets().size(), "搜索响应应能解析出推文");
        assertEquals("CURSOR_NEXT", fromSearch.nextCursor());

        // TweetDetail：data.threaded_conversation_with_injections_v2.instructions
        ObjectNode thread = MAPPER.createObjectNode();
        thread.set("instructions", instructions);
        ObjectNode threadData = MAPPER.createObjectNode();
        threadData.set("threaded_conversation_with_injections_v2", thread);
        JsonNode threadPage = MAPPER.createObjectNode().set("data", threadData);
        assertEquals(1, new TimelineParser(true, false).parse(threadPage).tweets().size(),
                "评论区响应应能解析出推文");
    }

    @Test
    @DisplayName("作者名落点：搜索结果为 core.screen_name（曾因此显示空作者）")
    void authorFromCoreScreenName() throws Exception {
        ObjectNode media = photoMedia();
        ArrayNode mediaArray = MAPPER.createArrayNode();
        mediaArray.add(media);

        ObjectNode legacy = MAPPER.createObjectNode();
        legacy.put("id_str", "333").put("created_at", CREATED_AT).put("full_text", "from search");
        legacy.set("extended_entities", MAPPER.createObjectNode().set("media", mediaArray));

        // 新版形状：rest_id + core.screen_name（没有 legacy.screen_name）
        ObjectNode userCore = MAPPER.createObjectNode().put("screen_name", "Susu0526m");
        ObjectNode userResult = MAPPER.createObjectNode().put("rest_id", "1650579264494874624");
        userResult.set("core", userCore);
        ObjectNode result = MAPPER.createObjectNode().put("rest_id", "333").set("legacy", legacy);
        result.set("core", MAPPER.createObjectNode().set("user_results",
                MAPPER.createObjectNode().set("result", userResult)));

        ObjectNode itemContent = MAPPER.createObjectNode();
        itemContent.set("tweet_results", MAPPER.createObjectNode().set("result", result));
        ObjectNode entry = MAPPER.createObjectNode().put("entryId", "tweet-333");
        entry.set("content", itemContent);
        ArrayNode entries = MAPPER.createArrayNode();
        entries.add(entry);

        Tweet tweet = new TimelineParser(true, false).parse(page(entries)).tweets().get(0);
        assertEquals("Susu0526m", tweet.userScreenName(), "应能从 core.screen_name 取到作者");
        assertEquals("1650579264494874624", tweet.userRestId());
    }

    // ------------------------------------------------------------ 字段兜底
    @Test
    @DisplayName("media_key 缺失用 URL 末段兜底；后缀从 path 段取")
    void mediaKeyFallbackAndExtension() {
        ObjectNode media = MAPPER.createObjectNode();
        media.put("type", "photo").put("media_url_https",
                "https://pbs.twimg.com/media/G8Sovr7bcAAyeIO.png?name=small");

        assertEquals("G8Sovr7bcAAyeIO", TimelineParser.mediaKeyOf(media));
        assertEquals("png", TimelineParser.photoExtension("https://pbs.twimg.com/media/a.png?name=small"));
        assertEquals("jpg", TimelineParser.photoExtension("https://pbs.twimg.com/media/a?name=small"));
        assertEquals("webp", TimelineParser.photoExtension("https://pbs.twimg.com/media/a.webp"));
        assertEquals("jpg", TimelineParser.photoExtension("https://pbs.twimg.com/media/a.heic"));
    }

    @Test
    @DisplayName("created_at：合法可解析、非法返回 0；无 cursor 时为 null")
    void createdAtAndCursor() {
        assertEquals(Instant.parse("2026-10-15T14:35:54Z").toEpochMilli(),
                TimelineParser.parseCreatedAt(CREATED_AT));
        assertEquals(0L, TimelineParser.parseCreatedAt("not a date"));
        assertEquals(0L, TimelineParser.parseCreatedAt(""));

        assertNull(new TimelineParser(true, false).parse(pageWithEntry(tweetEntry(photoMedia()))).nextCursor(),
                "没有 cursor-bottom 时 nextCursor 应为 null");
    }

    // ------------------------------------------------------------ 合成样例辅助
    private ObjectNode photoMedia() {
        ObjectNode m = MAPPER.createObjectNode();
        m.put("type", "photo").put("media_key", "3_photo")
                .put("media_url_https", "https://pbs.twimg.com/media/photo1.jpg");
        return m;
    }

    private ObjectNode videoMedia(String type, ArrayNode variants) {
        ObjectNode m = MAPPER.createObjectNode();
        m.put("type", type).put("media_key", "7_1");
        m.set("video_info", MAPPER.createObjectNode().set("variants", variants));
        return m;
    }

    private ObjectNode tweetEntry(ObjectNode singleMedia) {
        ArrayNode mediaArray = MAPPER.createArrayNode();
        mediaArray.add(singleMedia);

        ObjectNode legacy = MAPPER.createObjectNode();
        legacy.put("id_str", "333").put("created_at", CREATED_AT).put("full_text", "hello");
        legacy.set("extended_entities", MAPPER.createObjectNode().set("media", mediaArray));

        ObjectNode result = MAPPER.createObjectNode();
        result.put("rest_id", "333").set("legacy", legacy);
        result.set("core", MAPPER.createObjectNode().set("user_results",
                MAPPER.createObjectNode().set("result", MAPPER.createObjectNode().put("rest_id", "42")
                        .set("legacy", MAPPER.createObjectNode().put("screen_name", "tester")))));

        ObjectNode entry = MAPPER.createObjectNode();
        entry.put("entryId", "tweet-333");
        entry.set("content", MAPPER.createObjectNode().set("itemContent",
                MAPPER.createObjectNode().set("tweet_results",
                        MAPPER.createObjectNode().set("result", result))));
        return entry;
    }

    private ObjectNode retweetEntry() {
        ArrayNode origMedia = MAPPER.createArrayNode();
        ObjectNode m = MAPPER.createObjectNode();
        m.put("type", "photo").put("media_key", "3_9").put("media_url_https", "https://pbs.twimg.com/media/orig.jpg");
        origMedia.add(m);

        ObjectNode origLegacy = MAPPER.createObjectNode();
        origLegacy.put("id_str", "111").put("created_at", CREATED_AT).put("full_text", "原推");
        origLegacy.set("extended_entities", MAPPER.createObjectNode().set("media", origMedia));
        ObjectNode origResult = MAPPER.createObjectNode();
        origResult.put("rest_id", "111").set("legacy", origLegacy);
        origResult.set("core", MAPPER.createObjectNode().set("user_results",
                MAPPER.createObjectNode().set("result", MAPPER.createObjectNode().put("rest_id", "9")
                        .set("legacy", MAPPER.createObjectNode().put("screen_name", "orig_author")))));

        ObjectNode wrapperLegacy = MAPPER.createObjectNode();
        wrapperLegacy.put("id_str", "222").put("created_at", CREATED_AT).put("full_text", "RT @orig_author 原推");
        wrapperLegacy.set("retweeted_status_result", MAPPER.createObjectNode().set("result", origResult));
        ObjectNode wrapper = MAPPER.createObjectNode();
        wrapper.put("rest_id", "222").set("legacy", wrapperLegacy);
        wrapper.set("core", MAPPER.createObjectNode().set("user_results",
                MAPPER.createObjectNode().set("result", MAPPER.createObjectNode().put("rest_id", "7")
                        .set("legacy", MAPPER.createObjectNode().put("screen_name", "retweeter")))));

        ObjectNode entry = MAPPER.createObjectNode();
        entry.put("entryId", "tweet-222");
        entry.set("content", MAPPER.createObjectNode().set("itemContent",
                MAPPER.createObjectNode().set("tweet_results",
                        MAPPER.createObjectNode().set("result", wrapper))));
        return entry;
    }

    private JsonNode pageWithVideo(String type, ArrayNode variants) {
        return pageWithCursor(tweetEntry(videoMedia(type, variants)));
    }

    private JsonNode pageWithCursor(ObjectNode tweetEntry) {
        ArrayNode entries = MAPPER.createArrayNode();
        entries.add(tweetEntry);
        entries.add(cursorEntry("cursor-bottom-9", "CURSOR_NEXT"));
        return page(entries);
    }

    private JsonNode pageWithEntry(ObjectNode entry) {
        ArrayNode entries = MAPPER.createArrayNode();
        entries.add(entry);
        return page(entries);
    }

    private ObjectNode cursorEntry(String entryId, String value) {
        ObjectNode e = MAPPER.createObjectNode();
        e.put("entryId", entryId);
        e.set("content", MAPPER.createObjectNode().put("value", value));
        return e;
    }

    private JsonNode page(ArrayNode entries) {
        ObjectNode instruction = MAPPER.createObjectNode();
        instruction.put("type", "TimelineAddEntries").set("entries", entries);
        ArrayNode instructions = MAPPER.createArrayNode();
        instructions.add(instruction);
        ObjectNode result = MAPPER.createObjectNode();
        result.set("timeline_v2", MAPPER.createObjectNode().set("timeline",
                MAPPER.createObjectNode().set("instructions", instructions)));
        return MAPPER.createObjectNode().set("data",
                MAPPER.createObjectNode().set("user", MAPPER.createObjectNode().set("result", result)));
    }
}
