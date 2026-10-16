package com.xinghuiTec.quartz.service;

import org.springframework.scheduling.support.CronExpression;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Converts a zoned Cron occurrence to a UTC DATETIME value. */
public final class JobSchedule {
    private JobSchedule() {
    }

    public static LocalDateTime nextUtc(String expression, String timeZone, Instant after) {
        var next = CronExpression.parse(expression).next(after.atZone(ZoneId.of(timeZone)));
        return next == null ? null : LocalDateTime.ofInstant(next.toInstant(), ZoneOffset.UTC);
    }

    public static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
