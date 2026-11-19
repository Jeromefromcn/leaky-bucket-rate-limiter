package com.example.ratelimiter.core;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable leaky-bucket rate limiter (leaky bucket as a meter).
 *
 * <p>State transitions return a new limiter; the input instance is never
 * modified. Buckets leak lazily: the level is recomputed from the elapsed time
 * whenever it is needed.
 */
public final class RateLimiter {

    private final double capacity;
    private final double leakRate;
    private final Map<String, Bucket> buckets;
    private final Clock clock;

    private RateLimiter(double capacity, double leakRate, Map<String, Bucket> buckets, Clock clock) {
        this.capacity = capacity;
        this.leakRate = leakRate;
        this.buckets = buckets;
        this.clock = clock;
    }

    public static RateLimiter create(double capacity, double leakRate) {
        return create(capacity, leakRate, Clock.systemUTC());
    }

    public static RateLimiter create(double capacity, double leakRate, Clock clock) {
        return new RateLimiter(capacity, leakRate, Map.of(), clock);
    }

    public double capacity() {
        return capacity;
    }

    public double leakRate() {
        return leakRate;
    }

    public AllowResult allowRequest(String userId, double timestamp) {
        Objects.requireNonNull(userId, "userId");

        Bucket existing = buckets.get(userId);
        double level = existing == null ? 0 : existing.level();
        if (level + 1 > capacity) {
            return new AllowResult(false, this);
        }
        double newLevel = level + 1;

        Map<String, Bucket> next = new HashMap<>(buckets);
        next.put(userId, new Bucket(newLevel, timestamp));
        return new AllowResult(true, new RateLimiter(capacity, leakRate, Map.copyOf(next), clock));
    }

    public Optional<BucketSnapshot> getBucketState(String userId) {
        Objects.requireNonNull(userId, "userId");
        Bucket bucket = buckets.get(userId);
        if (bucket == null) {
            return Optional.empty();
        }
        return Optional.of(new BucketSnapshot(
                userId, capacity, leakRate, bucket.level(), 0, bucket.lastUpdated()));
    }
}
