package com.aicomm.telegram;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramRateLimiterTest {

    @Test
    void firstCall_allowsImmediately() {
        var limiter = new TelegramRateLimiter(120, 25);

        assertThat(limiter.acquireOrGetDelay()).isEqualTo(0);
        assertThat(limiter.remainingToday()).isEqualTo(24);
    }

    @Test
    void secondCall_returnsWaitTime() {
        var limiter = new TelegramRateLimiter(120, 25);

        limiter.acquireOrGetDelay(); // first — allowed
        long wait = limiter.acquireOrGetDelay(); // second — must wait

        assertThat(wait).isGreaterThan(0);
        assertThat(wait).isLessThanOrEqualTo(120);
    }

    @Test
    void dailyLimit_returnsNegative() {
        var limiter = new TelegramRateLimiter(0, 2); // interval=0, max 2/day

        assertThat(limiter.acquireOrGetDelay()).isEqualTo(0); // 1st
        assertThat(limiter.acquireOrGetDelay()).isEqualTo(0); // 2nd
        assertThat(limiter.acquireOrGetDelay()).isEqualTo(-1); // 3rd — blocked
    }

    @Test
    void remainingToday_decrements() {
        var limiter = new TelegramRateLimiter(0, 5);

        assertThat(limiter.remainingToday()).isEqualTo(5);
        limiter.acquireOrGetDelay();
        assertThat(limiter.remainingToday()).isEqualTo(4);
        limiter.acquireOrGetDelay();
        assertThat(limiter.remainingToday()).isEqualTo(3);
    }
}
