package com.waller.bank_sim.webhook.service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** In-memory fixed-window counter per key. State is per instance and is lost on restart. */
class RateLimiter {

    private record Window(Instant start, int count) {
    }

    private final int maxAttempts;
    private final Duration window;
    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

    RateLimiter(int maxAttempts, Duration window) {
        this.maxAttempts = maxAttempts;
        this.window = window;
    }

    /** Counts an attempt and returns whether it is within the limit. */
    boolean tryAcquire(String key) {
        Instant now = Instant.now();
        Window current = windows.compute(key, (k, w) -> w == null || isExpired(w, now)
                ? new Window(now, 1)
                : new Window(w.start(), w.count() + 1));
        return current.count() <= maxAttempts;
    }

    void evictExpired() {
        Instant now = Instant.now();
        windows.values().removeIf(w -> isExpired(w, now));
    }

    private boolean isExpired(Window w, Instant now) {
        return !now.isBefore(w.start().plus(window));
    }
}
