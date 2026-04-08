package com.aicomm.schedule;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

@ConfigurationProperties(prefix = "app.schedule")
public record WorkScheduleProperties(
        LocalTime workStart,
        LocalTime workEnd,
        ZoneId timezone,
        Set<DayOfWeek> workDays,
        long morningDelayMinMs,
        long morningDelayMaxMs
) {}
