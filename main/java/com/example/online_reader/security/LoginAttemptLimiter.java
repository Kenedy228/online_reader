package com.example.online_reader.security;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Fixed windows, monotonic time and bounded memory. No username/password state is stored. */
public final class LoginAttemptLimiter {
    private final int maxAttempts;
    private final int maxClients;
    private final long windowNanos;
    private final LongSupplier ticker;
    private final Map<String, Window> clients = new HashMap<>();

    public LoginAttemptLimiter(int maxAttempts, int maxClients, Duration window) {
        this(maxAttempts, maxClients, window, System::nanoTime);
    }

    LoginAttemptLimiter(int maxAttempts, int maxClients, Duration window, LongSupplier ticker) {
        if (maxAttempts < 1 || maxAttempts > 100 || maxClients < 1 || maxClients > 4096
                || window == null || window.compareTo(Duration.ofSeconds(1)) < 0
                || window.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("Invalid login rate-limit policy");
        }
        this.maxAttempts = maxAttempts;
        this.maxClients = maxClients;
        this.windowNanos = window.toNanos();
        this.ticker = ticker;
    }

    /** Reserves one attempt; zero means allowed, otherwise the retry delay in seconds. */
    public synchronized long reserveAttempt(String address) {
        long now = ticker.getAsLong();
        clients.values().removeIf(window -> now - window.started >= windowNanos);
        String key = address == null || address.length() > 64 ? "unknown" : address;
        Window window = clients.get(key);
        if (window == null) {
            if (clients.size() >= maxClients) {
                // Do not evict live counters: rotating addresses must not reset another budget.
                long remaining = clients.values().stream()
                        .mapToLong(value -> windowNanos - (now - value.started)).min().orElse(windowNanos);
                return seconds(remaining);
            }
            window = new Window(now);
            clients.put(key, window);
        }
        if (window.attempts >= maxAttempts) {
            return seconds(windowNanos - (now - window.started));
        }
        window.attempts++;
        return 0;
    }

    private static long seconds(long nanos) {
        return Math.max(1, (nanos + 999_999_999L) / 1_000_000_000L);
    }

    private static final class Window {
        final long started;
        int attempts;

        Window(long started) { this.started = started; }
    }
}
