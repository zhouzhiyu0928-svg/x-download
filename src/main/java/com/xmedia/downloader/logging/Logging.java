package com.xmedia.downloader.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import com.xmedia.downloader.config.AppConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按配置应用日志（S2/H4）：{@code log.level} / {@code log.file} / {@code log.max-history}。
 *
 * <p>logback.xml 只负责控制台；文件输出与级别在这里动态挂载，才能跟随 config.yaml 生效
 * （配置文件里的 log.* 之前是「写了没用」的死配置）。
 */
public final class Logging {

    private static final Logger log = LoggerFactory.getLogger(Logging.class);

    /** 同一进程只挂一次文件 appender（loadConfig 可能被多处调用）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean APPLIED =
            new java.util.concurrent.atomic.AtomicBoolean();

    private Logging() {
    }

    public static void apply(AppConfig.LogConfig config) {
        if (Boolean.getBoolean("xmd.noFileLog")) {
            return;   // 测试环境：临时目录里的日志文件会被 appender 占住，导致 @TempDir 清理失败
        }
        if (!APPLIED.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx)) {
                return;
            }
            ch.qos.logback.classic.Logger root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
            root.setLevel(Level.toLevel(config.level(), Level.INFO));

            Path file = Path.of(config.file()).toAbsolutePath();
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }

            PatternLayoutEncoder encoder = new PatternLayoutEncoder();
            encoder.setContext(ctx);
            encoder.setPattern("%d{yyyy-MM-dd HH:mm:ss} %-5level %logger{24} - %msg%n");
            encoder.setCharset(java.nio.charset.StandardCharsets.UTF_8);
            encoder.start();

            RollingFileAppender<ILoggingEvent> appender = new RollingFileAppender<>();
            appender.setContext(ctx);
            appender.setName("FILE");
            appender.setFile(file.toString());
            appender.setEncoder(encoder);

            // 注意顺序：rolling policy 必须在 start() 前挂上 parent（否则 rawFileProperty() 取不到文件而抛错）
            TimeBasedRollingPolicy<ILoggingEvent> policy = new TimeBasedRollingPolicy<>();
            policy.setContext(ctx);
            policy.setFileNamePattern(file + ".%d{yyyy-MM-dd}.gz");
            policy.setMaxHistory(config.maxHistory());
            policy.setParent(appender);
            appender.setRollingPolicy(policy);
            policy.start();
            appender.start();

            root.addAppender(appender);
            log.info("日志文件：{}（级别 {}，保留 {} 天）", file, config.level(), config.maxHistory());
        } catch (Exception e) {
            // 日志配置失败不应阻断主流程
            System.err.println("[警告] 日志文件配置失败：" + e.getMessage());
        }
    }
}
