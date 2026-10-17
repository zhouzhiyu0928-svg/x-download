package com.xmedia.downloader.console;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

/** 把日志回灌到操作台（页面「实时日志」框）；由 {@link ConsoleServer} 动态挂到 root logger。 */
public final class UiLogAppender extends AppenderBase<ILoggingEvent> {

    private final RunState state;

    public UiLogAppender(RunState state) {
        this.state = state;
    }

    @Override
    protected void append(ILoggingEvent event) {
        String level = event.getLevel().toString();
        if ("DEBUG".equals(level) || "TRACE".equals(level)) {
            return;
        }
        state.log("[" + level + "] " + event.getFormattedMessage());
    }
}
