package dev.remoteoffload.server.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token-bucket rate limiter por clave (IP o cliente).
 */
public final class RateLimiter {

    private final double ratePerSec;
    private final double burst;

    private static final class Bucket {
        double tokens;
        long lastNanos;
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(double ratePerSec, double burst) {
        this.ratePerSec = ratePerSec;
        this.burst = burst;
    }

    public boolean allow(String key) {
        long now = System.nanoTime();
        Bucket b = buckets.computeIfAbsent(key, k -> {
            Bucket nb = new Bucket();
            nb.tokens = burst;
            nb.lastNanos = now;
            return nb;
        });
        synchronized (b) {
            double elapsed = (now - b.lastNanos) / 1_000_000_000.0;
            b.tokens = Math.min(burst, b.tokens + elapsed * ratePerSec);
            b.lastNanos = now;
            if (b.tokens >= 1.0) {
                b.tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    public int size() {
        return buckets.size();
    }
}