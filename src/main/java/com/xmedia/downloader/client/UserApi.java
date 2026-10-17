package com.xmedia.downloader.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.xmedia.downloader.account.Account;
import com.xmedia.downloader.error.AuthException;
import com.xmedia.downloader.error.UserNotFoundException;
import com.xmedia.downloader.model.User;
import com.xmedia.downloader.model.UserProfile;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 用户 / 搜索 / 评论区 相关接口封装（所有请求都经 GraphQLClient 走配额与限流）。 */
public final class UserApi {

    private static final Pattern STATUS_ID = Pattern.compile("/status/(\\d+)");

    private final GraphQLClient client;
    private final int pageSize;

    public UserApi(GraphQLClient client, int pageSize) {
        this.client = client;
        this.pageSize = pageSize;
    }

    /** UserByScreenName → rest_id（稳定键；screen_name 会改名，rest_id 不会）。 */
    public User resolve(Account account, String screenName) {
        JsonNode root = client.get(account, "UserByScreenName", Map.of("screen_name", screenName), screenName);
        JsonNode errors = root.path("errors");
        JsonNode result = root.path("data").path("user").path("result");
        if (result.isMissingNode() || result.isNull() || result.isEmpty()) {
            // 明确的「查无此人」：X 用 errors 数组或空 user 节点表达；这类 ID 应被标记为无效
            String detail = errors.isArray() && !errors.isEmpty()
                    ? "（" + errors.path(0).path("message").asText("") + "）" : "";
            throw new UserNotFoundException(screenName, "用户 @" + screenName + " 不存在" + detail);
        }
        String typename = result.path("__typename").asText("User");
        if (!"User".equals(typename)) {
            if (typename.contains("Unavailable") || typename.contains("Suspended")
                    || typename.contains("Restricted")) {
                // 注销/停用/受限：对下载任务而言同样是「无效 ID」，但原因要如实写出来
                throw new UserNotFoundException(screenName,
                        "用户 @" + screenName + " 不可用（" + typename + "）");
            }
            throw new AuthException(account.name(),
                    "无法获取用户 @" + screenName + "（" + typename + "）：账号可能不存在或被限制");
        }
        String restId = result.path("rest_id").asText("");
        if (restId.isBlank()) {
            throw new UserNotFoundException(screenName, "用户 @" + screenName + " 响应缺少 rest_id（ID 可能无效）");
        }
        JsonNode legacy = result.path("legacy");
        // 2026-10 起 UserByScreenName 换新版 schema：legacy 整体消失，字段搬到 core / tweet_counts
        JsonNode core = result.path("core");
        JsonNode counts = result.path("tweet_counts");
        String name = core.path("name").asText(legacy.path("name").asText(screenName));
        int statuses = legacy.has("statuses_count") ? legacy.path("statuses_count").asInt(0)
                : counts.path("tweets").asInt(0);
        int media = legacy.has("media_count") ? legacy.path("media_count").asInt(0)
                : counts.path("media_tweets").asInt(0);
        return new User(restId, screenName, name, statuses, media);
    }

    /** 主页信息（头像 / banner / 简介，F10）：用带 avatar/banner 字段的 profile 版 UserByScreenName。 */
    public UserProfile profile(Account account, String screenName) {
        JsonNode root = client.get(account, "UserProfile", Map.of("screen_name", screenName), screenName);
        JsonNode result = root.path("data").path("user").path("result");
        JsonNode legacy = result.path("legacy");           // 旧 schema（可能不存在）
        JsonNode core = result.path("core");               // 新 schema 的昵称/screen_name
        String avatar = result.path("avatar").path("image_url").asText("");
        if (avatar.isBlank()) {
            avatar = legacy.path("profile_image_url_https").asText("");
        }
        // 头像升级到 400x400（原版做法：_normal → _400x400）
        avatar = avatar.replaceAll("_normal(\\.\\w+)$", "_400x400$1");
        String banner = result.path("banner").path("image_url").asText("");
        if (banner.isBlank()) {
            banner = legacy.path("profile_banner_url").asText("");
        }
        if (!banner.isBlank() && !banner.matches(".*\\.(jpg|jpeg|png|webp)$")) {
            // 新版 banner 地址无扩展名（…/profile_banners/<id>/<ts>），需补尺寸后缀才拿得到图
            banner = banner.replaceAll("/+$", "") + "/1500x500";
        }
        String description = result.path("profile_bio").path("description").asText("");
        if (description.isBlank()) {
            description = legacy.path("description").asText("");
        }
        return new UserProfile(result.path("rest_id").asText(""), screenName,
                core.path("name").asText(legacy.path("name").asText(screenName)), description,
                avatar, banner.isBlank() ? null : banner,
                legacy.has("statuses_count") ? legacy.path("statuses_count").asInt(0)
                        : result.path("tweet_counts").path("tweets").asInt(0),
                legacy.has("media_count") ? legacy.path("media_count").asInt(0)
                        : result.path("tweet_counts").path("media_tweets").asInt(0));
    }

    /** UserMedia 一页（用户媒体时间线）。 */
    public JsonNode mediaPage(Account account, User user, String cursor, Integer count) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("userId", user.restId());
        vars.put("count", count == null ? pageSize : count);
        if (cursor != null && !cursor.isBlank()) {
            vars.put("cursor", cursor);
        }
        return client.get(account, "UserMedia", vars, user.screenName());
    }

    /** UserTweets 一页（含文本，纯文本任务用）。 */
    public JsonNode tweetsPage(Account account, User user, String cursor, Integer count) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("userId", user.restId());
        vars.put("count", count == null ? pageSize : count);
        if (cursor != null && !cursor.isBlank()) {
            vars.put("cursor", cursor);
        }
        return client.get(account, "UserTweets", vars, user.screenName());
    }

    /**
     * SearchTimeline 一页（Tag / 高级搜索，F7）。
     *
     * @param rawQuery 形如 {@code "#猫 filter:images"}（调用方负责拼 filter）
     * @param product  Latest / Top
     */
    public JsonNode searchTimeline(Account account, String rawQuery, String cursor, int count, String product) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("rawQuery", rawQuery);
        vars.put("count", count);
        vars.put("querySource", "typed_query");
        vars.put("product", product);
        if (cursor != null && !cursor.isBlank()) {
            vars.put("cursor", cursor);
        }
        return client.get(account, "SearchTimeline", vars, null);
    }

    /** TweetDetail 一页（评论区，F8）。 */
    public JsonNode tweetDetail(Account account, String focalTweetId, String cursor) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("focalTweetId", focalTweetId);
        if (cursor != null && !cursor.isBlank()) {
            vars.put("cursor", cursor);
        }
        return client.get(account, "TweetDetail", vars, null);
    }

    /** 从推文链接或裸 ID 里取推文 ID。 */
    public static String extractTweetId(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("推文链接/ID 不能为空");
        }
        Matcher m = STATUS_ID.matcher(input);
        if (m.find()) {
            return m.group(1);
        }
        String trimmed = input.trim();
        if (trimmed.matches("\\d+")) {
            return trimmed;
        }
        throw new IllegalArgumentException("无法从 '" + input + "' 解析推文 ID（示例：https://x.com/user/status/123456）");
    }
}
