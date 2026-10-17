package com.xmedia.downloader.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** queryId 自更新（需求 §7 头号风险的应对）：bundle 解析 + 文件回写逻辑。 */
class QueryIdRefresherTest {

    private static final String YAML = """
            bearer: "x"
            operations:
              UserByScreenName:
                base-url: https://twitter.com
                path: /i/api/graphql/OLD1/UserByScreenName
                method: get
              SearchTimeline:
                base-url: https://x.com
                path: /i/api/graphql/OLD2/SearchTimeline
                method: post
              UserProfile:
                base-url: https://twitter.com
                path: /i/api/graphql/OLD1/UserByScreenName
                method: get
              GhostOperation:
                path: /i/api/graphql/OLD4/GhostOperation
            """;

    @Test
    @DisplayName("从 bundle 文本解析「操作名 → queryId」表")
    void parsesOperationIds() {
        String bundle = """
                a=1;e.exports={queryId:"irezafrnzav4Ulf1vDkEBg",operationName:"SearchTimeline",operationType:"query"};
                b=2;e.exports={queryId:"QWOimofaocjeRda7Tm5pNA",operationName:"UserMedia"};
                d=4;e.exports={queryId:"dup",operationName:"UserMedia"};
                """;
        Map<String, String> ids = QueryIdRefresher.parseOperationIds(bundle);

        assertEquals("irezafrnzav4Ulf1vDkEBg", ids.get("SearchTimeline"));
        assertEquals("QWOimofaocjeRda7Tm5pNA", ids.get("UserMedia"), "同名应取首个（与客户端一致）");
        assertEquals(2, ids.size());
    }

    @Test
    @DisplayName("bundle 没有操作表时返回空表（由调用方报错）")
    void emptyWhenNoOperations() {
        assertTrue(QueryIdRefresher.parseOperationIds("<html>not a bundle</html>").isEmpty());
    }

    @Test
    @DisplayName("回写：只改过期的 queryId；UserProfile 复用 UserByScreenName 的 id；缺失的记 notFound")
    void applyQids() {
        Map<String, String> fresh = Map.of(
                "UserByScreenName", "NEW1",
                "SearchTimeline", "NEW2",
                "GhostOperation", "NEW4");

        QueryIdRefresher.ApplyResult r = QueryIdRefresher.applyQids(YAML, fresh);

        assertTrue(r.text().contains("/graphql/NEW1/UserByScreenName"), r.text());
        assertTrue(r.text().contains("/graphql/NEW2/SearchTimeline"), r.text());
        assertTrue(r.text().contains("/graphql/NEW1/UserByScreenName\n    method: get\n  GhostOperation")
                        || r.text().contains("/graphql/NEW1/UserByScreenName")
                        && r.text().split("UserProfile:")[1].contains("NEW1"),
                "UserProfile 应复用 UserByScreenName 的新 id：" + r.text());
        assertTrue(r.text().contains("/graphql/NEW4/GhostOperation"), r.text());
        // UserByScreenName / SearchTimeline / UserProfile（复用前者 id）/ GhostOperation 都会更新
        assertEquals(4, r.updated().size(), r.updated().toString());
        assertEquals("OLD1 -> NEW1", r.updated().get("UserProfile"), "UserProfile 复用 UserByScreenName 的 id");
        assertTrue(r.notFound().isEmpty(), r.notFound().toString());
    }

    @Test
    @DisplayName("CRLF 文件也能定位块（Windows 上 patch 工具改过的文件会变成 CRLF，曾因此全部失配）")
    void handlesCrlf() {
        String crlf = YAML.replace("\n", "\r\n");
        QueryIdRefresher.ApplyResult r = QueryIdRefresher.applyQids(crlf,
                Map.of("UserByScreenName", "NEW1", "SearchTimeline", "NEW2", "GhostOperation", "NEW4"));

        assertTrue(r.notFound().isEmpty(), "CRLF 不应导致失配：" + r.notFound());
        assertEquals(4, r.updated().size(), r.updated().toString());
        assertTrue(r.text().contains("/graphql/NEW2/SearchTimeline"), r.text());
        assertFalse(r.text().contains("\r"), "回写文本应统一为 LF");
    }

    @Test
    @DisplayName("回写：已是最新则不动（updated 为空）")
    void applyQidsNoChange() {
        Map<String, String> same = Map.of("UserByScreenName", "OLD1", "SearchTimeline", "OLD2",
                "GhostOperation", "OLD4");

        QueryIdRefresher.ApplyResult r = QueryIdRefresher.applyQids(YAML, same);

        assertEquals(YAML, r.text(), "全部为最新时文本不应变化");
        assertTrue(r.updated().isEmpty(), r.updated().toString());
        assertTrue(r.notFound().isEmpty());
    }
}
