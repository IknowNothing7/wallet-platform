package com.waller.wallet_platform.security;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory fixed-window counter per key. State is per application instance and is lost on restart;
 * move it to a shared store (e.g. Redis) before running more than one instance.
 */
public class RateLimiter {

    private record Window(Instant start, int count) {
    }

    private final int maxAttempts;
    private final Duration window;
    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimiter(int maxAttempts, Duration window) {
        this.maxAttempts = maxAttempts;
        this.window = window;
    }

    /** Counts an attempt and returns whether it is within the limit. */
    public boolean tryAcquire(String key) {
        return hit(key).count() <= maxAttempts;
    }

    /** Counts an attempt without checking the limit (e.g. a failed login). */
    public void record(String key) {
        hit(key);
    }

    /** Whether the key has used up its attempts in the current window, without counting a new one. */
    public boolean isLimited(String key) {
        Window current = windows.get(key);
        return current != null && !isExpired(current, Instant.now()) && current.count() >= maxAttempts;
    }

    public void reset(String key) {
        windows.remove(key);
    }

    public long secondsUntilReset(String key) {
        Window current = windows.get(key);
        if (current == null) {
            return 0;
        }
        long seconds = Duration.between(Instant.now(), current.start().plus(window)).toSeconds();
        return Math.max(seconds, 1);
    }

    public void evictExpired() {
        Instant now = Instant.now();
        windows.values().removeIf(w -> isExpired(w, now));
    }

    private Window hit(String key) {
        Instant now = Instant.now();
        return windows.compute(key, (k, current) -> current == null || isExpired(current, now)
                ? new Window(now, 1)
                : new Window(current.start(), current.count() + 1));
    }

    private boolean isExpired(Window w, Instant now) {
        return !now.isBefore(w.start().plus(window));
    }

}
