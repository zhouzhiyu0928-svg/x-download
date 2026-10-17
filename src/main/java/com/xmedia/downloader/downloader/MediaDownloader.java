package com.xmedia.downloader.downloader;

import com.xmedia.downloader.config.AppConfig;
import com.xmedia.downloader.error.DownloadException;
import com.xmedia.downloader.model.MediaItem;
import com.xmedia.downloader.util.NamingTemplate;
import com.xmedia.downloader.util.PathSanitizer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 媒体下载器：{@code *.part} 临时文件 + Range 断点续传 + 校验后原子改名（S4）。
 *
 * <p>三条硬护栏（前两条是真实事故的直接产物）：
 * <ol>
 *   <li>写盘前 {@link PathSanitizer#guard}：拒绝「盘符之后含冒号」的路径（否则被写成 NTFS 数据流，0 字节）；</li>
 *   <li>写盘后校验字节数 &gt; 0，否则按失败处理（不留"假成功"记录）；</li>
 *   <li>图片按 image-format 取图，404 时逐级回退 orig → 4096x4096 → 原样。</li>
 * </ol>
 */
public final class MediaDownloader {

    private static final Logger log = LoggerFactory.getLogger(MediaDownloader.class);
    /** 单个候选 URL 的最大重试次数（与原版「重试到成功或放弃」相比更保守，避免长期挂死） */
    private static final int MAX_ATTEMPTS = 3;

    /** @param path 落盘路径；@param bytes 实际字节数 */
    public record Outcome(boolean success, Path path, long bytes, String error) {

        public static Outcome failure(Path path, String error) {
            return new Outcome(false, path, 0L, error);
        }
    }

    private final OkHttpClient http;
    private final AppConfig.DownloadConfig config;
    private final NamingTemplate naming;

    public MediaDownloader(OkHttpClient http, AppConfig.DownloadConfig config, NamingTemplate naming) {
        this.http = http;
        this.config = config;
        this.naming = naming;
    }

    /**
     * 下载一个媒体。
     *
     * @param saveRoot 保存根目录（模板里的 {user} 会再建一层子目录）
     * @param index    同名冲突时的序号（{index} 变量）
     */
    public Outcome download(MediaItem item, Path saveRoot, int index) {
        String relative = naming.render(item, index);
        Path target = Path.of(PathSanitizer.guard(saveRoot.resolve(relative).toString()));
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            return Outcome.failure(target, "创建目录失败：" + e.getMessage());
        }

        Path part = target.resolveSibling(target.getFileName() + ".part");
        String lastError = null;
        for (String url : candidateUrls(item)) {
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    long bytes = fetch(url, part, target);
                    return new Outcome(true, target, bytes, null);
                } catch (NotFound e) {
                    lastError = "HTTP 404：" + url;
                    break;            // 该 URL 取不到，换下一个候选
                } catch (IOException | DownloadException e) {
                    lastError = e.getMessage();
                    log.warn("下载失败（第 {} 次）：{} → {}", attempt, target.getFileName(), lastError);
                    if (attempt >= 3) {
                        break;
                    }
                    sleepQuietly(300L * attempt);
                }
            }
        }
        return Outcome.failure(target, lastError == null ? "未知失败" : lastError);
    }

    /** 按 image-format 生成候选 URL（图片才加 name/format 参数；视频原样）。 */
    List<String> candidateUrls(MediaItem item) {
        List<String> urls = new ArrayList<>();
        if (!item.type().equals(com.xmedia.downloader.model.MediaType.IMAGE)) {
            urls.add(item.url());
            return urls;
        }
        String format = config.imageFormat();
        switch (format) {
            case "orig" -> {
                urls.add(withQuery(item.url(), "name=orig"));
                urls.add(withQuery(item.url(), "format=jpg&name=4096x4096"));
                urls.add(item.url());
            }
            case "4096x4096" -> {
                urls.add(withQuery(item.url(), "format=jpg&name=4096x4096"));
                urls.add(withQuery(item.url(), "name=orig"));
                urls.add(item.url());
            }
            case "png" -> {
                urls.add(withQuery(item.url(), "format=png&name=4096x4096"));
                urls.add(withQuery(item.url(), "format=png&name=orig"));
                urls.add(item.url());
            }
            default -> {
                urls.add(withQuery(item.url(), "format=jpg&name=4096x4096"));
                urls.add(withQuery(item.url(), "name=orig"));
                urls.add(item.url());
            }
        }
        return urls;
    }

    private static String withQuery(String url, String query) {
        return url + (url.contains("?") ? "&" : "?") + query;
    }

    /** 流式落盘；返回最终字节数。 */
    private long fetch(String url, Path part, Path target) throws IOException {
        long existing = 0L;
        if (config.resumeEnabled() && Files.exists(part)) {
            existing = Files.size(part);
        }
        Request.Builder rb = new Request.Builder().url(url).get()
                .header("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36");
        if (existing > 0) {
            rb.header("range", "bytes=" + existing + "-");
        }
        try (Response response = http.newCall(rb.build()).execute()) {
            int code = response.code();
            if (code == 404 || code == 410) {
                throw new NotFound("HTTP " + code);
            }
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + code);
            }
            boolean append = code == 206 && existing > 0;
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("响应无内容");
            }
            long written = existing;
            try (InputStream in = body.byteStream();
                 OutputStream out = Files.newOutputStream(part, append
                         ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                         : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                             StandardOpenOption.WRITE})) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    written += n;
                }
            }
            if (written <= 0) {
                throw new DownloadException("写入后字节数为 0（护栏：拒绝假成功）");
            }
            try {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return written;
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 404/410：换候选 URL，不重试同一地址。 */
    private static final class NotFound extends IOException {
        NotFound(String message) {
            super(message);
        }
    }
}
