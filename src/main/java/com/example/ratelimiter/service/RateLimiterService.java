package com.example.ratelimiter.service;

import com.example.ratelimiter.config.RateLimiterProperties;
import com.example.ratelimiter.core.AllowResult;
import com.example.ratelimiter.core.BucketSnapshot;
import com.example.ratelimiter.core.RateLimiter;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Imperative shell around the immutable {@link RateLimiter}.
 *
 * <p>Writes are serialized by {@code writeLock}; reads take a volatile snapshot
 * of the immutable limiter and need no lock.
 */
@Service
public class RateLimiterService {

    private final Clock clock;
    private final Object writeLock = new Object();
    private volatile RateLimiter current;

    public RateLimiterService(RateLimiterProperties properties, Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.current = RateLimiter.create(properties.capacity(), properties.leakRate(), clock);
    }

    public Decision allow(String userId) {
        requireUserId(userId);
        synchronized (writeLock) {
            double now = epochSeconds(clock);
            AllowResult result = current.allowRequest(userId, now);
            current = result.limiter();
            BucketSnapshot snapshot = result.limiter().getBucketState(userId)
                    .orElseThrow(() -> new IllegalStateException(
                            "bucket missing after request for user " + userId));
            return new Decision(result.allowed(), snapshot);
        }
    }

    public Optional<BucketSnapshot> getBucketState(String userId) {
        requireUserId(userId);
        return current.getBucketState(userId);
    }

    private static void requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new InvalidUserIdException("userId must not be blank");
        }
    }

    private static double epochSeconds(Clock clock) {
        Instant instant = clock.instant();
        return instant.getEpochSecond() + instant.getNano() / 1_000_000_000.0;
    }

    public record Decision(boolean allowed, BucketSnapshot bucket) {
    }
}
