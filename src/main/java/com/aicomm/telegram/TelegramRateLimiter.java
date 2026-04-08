package com.aicomm.telegram;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Rate limiter for Telegram first-contact messages to prevent account bans.
 *
 * Two limits:
 * 1. Minimum interval between first contacts (e.g. 120 seconds)
 * 2. Maximum first contacts per day (e.g. 25)
 *
 * Does NOT limit replies to existing conversations — those have much softer Telegram limits.
 */
@Slf4j
@Component
public class TelegramRateLimiter {

    private final int intervalSec;
    private final int maxPerDay;

    private volatile long lastFirstContactMs = 0;
    private final AtomicInteger dailyCount = new AtomicInteger(0);
    private final AtomicReference<LocalDate> currentDay = new AtomicReference<>(LocalDate.now());

    public TelegramRateLimiter(
            @Value("${app.rate-limit.first-contact-interval-sec:120}") int intervalSec,
            @Value("${app.rate-limit.max-first-contacts-per-day:25}") int maxPerDay) {
        this.intervalSec = intervalSec;
        this.maxPerDay = maxPerDay;
    }

    /**
     * Checks if a first-contact message can be sent now.
     * If not, returns the number of seconds to wait.
     *
     * @return 0 if allowed, positive number = seconds to wait
     */
    public synchronized long acquireOrGetDelay() {
        resetDailyCountIfNewDay();

        // Daily limit check
        if (dailyCount.get() >= maxPerDay) {
            log.warn("Daily first-contact limit reached ({}/{})", dailyCount.get(), maxPerDay);
            return -1; // Signal: cannot send today
        }

        // Interval check
        long now = System.currentTimeMillis();
        long elapsed = now - lastFirstContactMs;
        long requiredMs = intervalSec * 1000L;

        if (elapsed < requiredMs) {
            long waitMs = requiredMs - elapsed;
            log.debug("Rate limit: need to wait {}s before next first contact", waitMs / 1000);
            return waitMs / 1000;
        }

        // Allowed — record this send
        lastFirstContactMs = now;
        dailyCount.incrementAndGet();
        log.info("First contact allowed ({}/{} today)", dailyCount.get(), maxPerDay);
        return 0;
    }

    /**
     * Returns remaining first contacts for today.
     */
    public int remainingToday() {
        resetDailyCountIfNewDay();
        return Math.max(0, maxPerDay - dailyCount.get());
    }

    private void resetDailyCountIfNewDay() {
        var today = LocalDate.now();
        if (!today.equals(currentDay.get())) {
            currentDay.set(today);
            dailyCount.set(0);
            log.info("Daily first-contact counter reset");
        }
    }
}
