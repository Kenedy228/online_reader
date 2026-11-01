package com.example.online_reader.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

class LoginAttemptLimiterTest {
    @Test
    void exhaustedWindowExpiresExactlyAndRetryRoundsUp() {
        var clock = new AtomicLong(100);
        var limiter = new LoginAttemptLimiter(2, 2, Duration.ofSeconds(2), clock::get);
        assertThat(limiter.reserveAttempt("a")).isZero();
        assertThat(limiter.reserveAttempt("a")).isZero();
        assertThat(limiter.reserveAttempt("a")).isEqualTo(2);
        clock.addAndGet(1_000_000_001L);
        assertThat(limiter.reserveAttempt("a")).isEqualTo(1);
        clock.addAndGet(999_999_999L);
        assertThat(limiter.reserveAttempt("a")).isZero();
    }

    @Test
    void capacityNeverEvictsLiveCountersAndExpiredCapacityIsReused() {
        var clock = new AtomicLong();
        var limiter = new LoginAttemptLimiter(1, 2, Duration.ofSeconds(10), clock::get);
        assertThat(limiter.reserveAttempt("a")).isZero();
        clock.addAndGet(1_000_000_000L);
        assertThat(limiter.reserveAttempt("b")).isZero();
        assertThat(limiter.reserveAttempt("c")).isEqualTo(9);
        assertThat(limiter.reserveAttempt("a")).isEqualTo(9);
        clock.addAndGet(9_000_000_000L);
        assertThat(limiter.reserveAttempt("c")).isZero();
        assertThat(limiter.reserveAttempt("b")).isEqualTo(1);
    }

    @Test
    void concurrentRequestsCannotOverspendBudget() throws Exception {
        var limiter = new LoginAttemptLimiter(10, 1, Duration.ofMinutes(1));
        try (var threads = Executors.newFixedThreadPool(8)) {
            var results = threads.invokeAll(IntStream.range(0, 100)
                    .<java.util.concurrent.Callable<Long>>mapToObj(i -> () -> limiter.reserveAttempt("same"))
                    .toList());
            long allowed = 0;
            for (var result : results) if (result.get() == 0) allowed++;
            assertThat(allowed).isEqualTo(10);
        }
    }

    @Test
    void monotonicCounterWrapAndUnboundedAddressInputsRemainSafe() {
        var clock = new AtomicLong(Long.MAX_VALUE - 5);
        var limiter = new LoginAttemptLimiter(1, 1, Duration.ofSeconds(1), clock::get);
        assertThat(limiter.reserveAttempt(null)).isZero();
        assertThat(limiter.reserveAttempt("x".repeat(1000))).isEqualTo(1);
        clock.addAndGet(1_000_000_000L);
        assertThat(limiter.reserveAttempt("new")).isZero();
    }

    @Test
    void rejectsUnboundedPolicies() {
        assertThatThrownBy(() -> new LoginAttemptLimiter(0, 1, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginAttemptLimiter(101, 1, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginAttemptLimiter(1, 4097, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginAttemptLimiter(1, 1, Duration.ofMillis(999)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginAttemptLimiter(1, 1, Duration.ofMinutes(16)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
