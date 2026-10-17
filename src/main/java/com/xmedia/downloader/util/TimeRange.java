package com.xmedia.downloader.util;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * 时间范围（配置 {@code time-range: "1990-01-01:2030-01-01"}）。
 *
 * <p>语义：闭区间按「日」计，起始取当日 00:00:00，结束取**次日** 00:00:00（含结束当天）。
 */
public record TimeRange(long startMillis, long endMillis) {

    public static final TimeRange UNLIMITED =
            new TimeRange(0L, LocalDate.of(2099, 12, 31).plusDays(1).atStartOfDay(ZoneId.systemDefault())
                    .toInstant().toEpochMilli());

    public static TimeRange parse(String text) {
        return parse(text, ZoneId.systemDefault());
    }

    public static TimeRange parse(String text, ZoneId zone) {
        if (text == null || text.isBlank()) {
            return UNLIMITED;
        }
        String[] parts = text.split(":");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("时间范围格式应为 yyyy-MM-dd:yyyy-MM-dd，实际：" + text);
        }
        try {
            LocalDate start = LocalDate.parse(parts[0].trim());
            LocalDate end = LocalDate.parse(parts[1].trim());
            if (end.isBefore(start)) {
                throw new IllegalArgumentException("时间范围结束早于开始：" + text);
            }
            return new TimeRange(start.atStartOfDay(zone).toInstant().toEpochMilli(),
                    end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("时间范围日期无法解析（应为 yyyy-MM-dd）：" + text, e);
        }
    }

    public boolean contains(long millis) {
        return millis >= startMillis && millis < endMillis;
    }

    @Override
    public String toString() {
        return "[" + startMillis + ", " + endMillis + ")";
    }
}
