package com.xmedia.downloader.downloader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xmedia.downloader.config.AppConfig;
import com.xmedia.downloader.model.MediaItem;
import com.xmedia.downloader.model.MediaType;
import com.xmedia.downloader.support.TestConfigs;
import com.xmedia.downloader.util.NamingTemplate;
import com.xmedia.downloader.util.PathSanitizer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 下载器：正常落盘、0 字节护栏、断点续传（Range）、404 候选回退、路径清洗。 */
class MediaDownloaderTest {

    @TempDir
    Path dir;

    private MockWebServer server;
    private OkHttpClient http;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        http = new OkHttpClient();
    }

    @AfterEach
    void tearDown() throws IOException {
        // 不关连接池的话 MockWebServer.shutdown() 会等到连接空闲（实测 6 个用例 84 秒）
        http.connectionPool().evictAll();
        http.dispatcher().executorService().shutdownNow();
        server.shutdown();
    }

    private MediaDownloader downloader() {
        AppConfig config = TestConfigs.minimal(dir.resolve("t.db").toString());
        return new MediaDownloader(http, config.download(), NamingTemplate.defaultTemplate());
    }

    private MediaItem image(String mediaKey, String url) {
        return new MediaItem("100", mediaKey, "tester", MediaType.IMAGE, url, "jpg",
                Instant.parse("2026-10-15T14:35:54Z").toEpochMilli(), null);
    }

    private Path targetPath(MediaItem item) {
        String relative = NamingTemplate.defaultTemplate().render(item, 0);
        return Path.of(PathSanitizer.guard(dir.resolve(relative).toString()));
    }

    private static Buffer bytes(int size, byte fill) {
        Buffer buffer = new Buffer();
        buffer.write(new byte[size], 0, size);
        return buffer;
    }

    @Test
    @DisplayName("正常下载：落盘、字节数一致、.part 清理干净")
    void happyPath() {
        server.enqueue(new MockResponse().setBody(bytes(1000, (byte) 'a')));
        MediaItem item = image("3_abc", server.url("/media/3_abc.jpg").toString());

        MediaDownloader.Outcome outcome = downloader().download(item, dir, 0);

        assertTrue(outcome.success(), outcome.error());
        assertEquals(1000L, outcome.bytes());
        assertEquals(1000L, outcome.path().toFile().length());
        assertFalse(Files.exists(outcome.path().resolveSibling(outcome.path().getFileName() + ".part")));
    }

    @Test
    @DisplayName("0 字节护栏：服务端返回空内容必须判失败，且不留目标文件")
    void zeroByteGuard() {
        // 用常驻 dispatcher：重试会发多次请求，若只用 enqueue 会在队列空时被阻塞（曾让本用例跑 83 秒）
        server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setBody("");
            }
        });
        MediaItem item = image("3_empty", server.url("/media/3_empty.jpg").toString());

        MediaDownloader.Outcome outcome = downloader().download(item, dir, 0);

        assertFalse(outcome.success(), "空响应不得算成功");
        assertFalse(Files.exists(targetPath(item)), "失败不得留下目标文件");
    }

    @Test
    @DisplayName("断点续传：已有 .part 时发 Range 并追加，最终大小 = 400 + 600")
    void resumeWithRange() throws Exception {
        MediaItem item = image("3_resume", server.url("/media/3_resume.jpg").toString());
        Path target = targetPath(item);
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        Files.write(part, new byte[400]);

        server.enqueue(new MockResponse().setResponseCode(206).setBody(bytes(600, (byte) 'b')));
        MediaDownloader.Outcome outcome = downloader().download(item, dir, 0);

        assertTrue(outcome.success(), outcome.error());
        assertEquals(1000L, outcome.bytes());
        RecordedRequest request = server.takeRequest();
        assertEquals("bytes=400-", request.getHeader("range"));
        assertEquals(1000L, Files.size(target));
    }

    @Test
    @DisplayName("404 回退：orig 取不到时依次尝试 4096x4096")
    void fallsBackOn404() {
        server.enqueue(new MockResponse().setResponseCode(404));
        server.enqueue(new MockResponse().setBody(bytes(500, (byte) 'c')));
        MediaItem item = image("3_404", server.url("/media/3_404.jpg").toString());

        MediaDownloader.Outcome outcome = downloader().download(item, dir, 0);

        assertTrue(outcome.success(), outcome.error());
        assertEquals(500L, outcome.bytes());
        assertEquals(2, server.getRequestCount(), "第二次应换候选 URL 而不是死磕同一个");
        try {
            server.takeRequest();
            assertTrue(server.takeRequest().getPath().contains("4096x4096"));
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("同一 URL 的 5xx 会重试，第 3 次成功即算成功")
    void retriesServerErrors() {
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setBody(bytes(700, (byte) 'd')));
        MediaItem item = image("3_retry", server.url("/media/3_retry.jpg").toString());

        MediaDownloader.Outcome outcome = downloader().download(item, dir, 0);

        assertTrue(outcome.success(), outcome.error());
        assertEquals(700L, outcome.bytes());
    }

    @Test
    @DisplayName("文件名清洗：mediaKey/用户名里的冒号不得进入路径（NTFS 数据流护栏）")
    void sanitizesIllegalCharsInPath() throws IOException {
        server.enqueue(new MockResponse().setBody(bytes(123, (byte) 'e')));
        MediaItem item = image("3_a:b", server.url("/media/3_ab.jpg").toString());

        MediaDownloader.Outcome outcome = downloader().download(item, dir, 0);

        assertTrue(outcome.success(), outcome.error());
        String path = outcome.path().toString();
        assertFalse(PathSanitizer.stripPrefix(path).contains(":"), path);
        assertTrue(path.contains("3_a-b"), path);
        assertEquals(123L, Files.size(outcome.path()));
    }
}
