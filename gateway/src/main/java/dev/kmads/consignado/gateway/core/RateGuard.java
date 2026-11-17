package dev.kmads.consignado.gateway.core;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A small per-key guard held in memory of one instance. The WAF does the real rate limit;
 * this only stops one user from repeating an expensive call many times in a short time.
 */
public final class RateGuard {
    private final int max;
    private final Duration period;
    private final Clock clock;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public RateGuard(int max, Duration period, Clock clock) {
        this.max = max;
        this.period = period;
        this.clock = clock;
    }

    /** Throws 429 when the key made more than max calls in the period. */
    public void check(String key) {
        long now = clock.millis();
        long from = now - period.toMillis();
        if (hits.size() > 5000) hits.clear();
        Deque<Long> deque = hits.computeIfAbsent(key == null ? "-" : key, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst() <= from) deque.pollFirst();
            if (deque.size() >= max) {
                int retry = (int) Math.max(1, (deque.peekFirst() + period.toMillis() - now) / 1000);
                throw new ApiException(429, "too-many-requests", "Too many requests. Try again later.", retry);
            }
            deque.addLast(now);
        }
    }
}
