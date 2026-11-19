package com.example.ratelimiter.core;

import com.example.ratelimiter.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    private static final double CAPACITY = 5;
    private static final double LEAK_RATE = 1.0;
    private static final double T0 = 1_700_000_000.0;
    private static final double DELTA = 1e-9;

    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.ofEpochSecond(1_700_000_000L));
    }

    @Test
    void firstRequestFromNewUserIsAllowed() {
        RateLimiter limiter = RateLimiter.create(CAPACITY, LEAK_RATE, clock);

        AllowResult result = limiter.allowRequest("user-1", T0);

        assertTrue(result.allowed());
        assertEquals(1.0, result.limiter().getBucketState("user-1").orElseThrow().level(), DELTA);
    }

    @Test
    void allowsBurstUpToCapacityThenRejects() {
        RateLimiter limiter = RateLimiter.create(CAPACITY, LEAK_RATE, clock);

        for (int i = 0; i < CAPACITY; i++) {
            AllowResult result = limiter.allowRequest("user-1", T0);
            assertTrue(result.allowed(), "request " + (i + 1) + " should be allowed");
            limiter = result.limiter();
        }

        AllowResult rejected = limiter.allowRequest("user-1", T0);

        assertFalse(rejected.allowed());
        assertSame(limiter, rejected.limiter());
    }

    @Test
    void allowsAgainOnceEnoughTimeHasLeaked() {
        RateLimiter full = fill("user-1");

        assertFalse(full.allowRequest("user-1", T0 + 0.5).allowed(),
                "only 0.5 units have leaked, the bucket is still too full");
        assertTrue(full.allowRequest("user-1", T0 + 1.0).allowed());
    }

    @Test
    void snapshotReportsEffectiveLevelAtQueryTime() {
        RateLimiter full = fill("user-1");
        clock.setEpochSeconds(T0 + 2.5);

        BucketSnapshot snapshot = full.getBucketState("user-1").orElseThrow();

        assertEquals("user-1", snapshot.userId());
        assertEquals(CAPACITY, snapshot.capacity(), DELTA);
        assertEquals(LEAK_RATE, snapshot.leakRate(), DELTA);
        assertEquals(2.5, snapshot.level(), DELTA);
        assertEquals(2.5, snapshot.remaining(), DELTA);
        assertEquals(T0, snapshot.lastUpdated(), DELTA);
    }

    @Test
    void levelNeverGoesBelowZero() {
        RateLimiter limiter = RateLimiter.create(CAPACITY, LEAK_RATE, clock)
                .allowRequest("user-1", T0).limiter();
        clock.setEpochSeconds(T0 + 100);

        BucketSnapshot snapshot = limiter.getBucketState("user-1").orElseThrow();
        assertEquals(0.0, snapshot.level(), DELTA);
        assertEquals(5.0, snapshot.remaining(), DELTA);

        for (int i = 0; i < CAPACITY; i++) {
            AllowResult result = limiter.allowRequest("user-1", T0 + 100);
            assertTrue(result.allowed());
            limiter = result.limiter();
        }
        assertFalse(limiter.allowRequest("user-1", T0 + 100).allowed());
    }

    @Test
    void rejectedRequestLeavesStoredStateUntouched() {
        RateLimiter full = fill("user-1");

        assertFalse(full.allowRequest("user-1", T0 + 0.5).allowed());

        clock.setEpochSeconds(T0 + 0.5);
        BucketSnapshot snapshot = full.getBucketState("user-1").orElseThrow();
        assertEquals(4.5, snapshot.level(), DELTA);
        assertEquals(T0, snapshot.lastUpdated(), DELTA);
    }

    @Test
    void backwardsTimestampIsTreatedAsNoElapsedTime() {
        RateLimiter limiter = RateLimiter.create(CAPACITY, LEAK_RATE, clock)
                .allowRequest("user-1", T0 + 10).limiter();

        AllowResult earlier = limiter.allowRequest("user-1", T0 + 5);

        assertTrue(earlier.allowed());
        clock.setEpochSeconds(T0 + 10);
        assertEquals(2.0, earlier.limiter().getBucketState("user-1").orElseThrow().level(), DELTA);
    }

    @Test
    void hugeLeakRateDoesNotProduceNaNOrInfinity() {
        RateLimiter limiter = RateLimiter.create(CAPACITY, 1e308, clock)
                .allowRequest("user-1", T0).limiter();
        clock.setEpochSeconds(T0 + 10);

        BucketSnapshot snapshot = limiter.getBucketState("user-1").orElseThrow();

        assertEquals(0.0, snapshot.level(), DELTA);
        assertEquals(5.0, snapshot.remaining(), DELTA);
    }

    private RateLimiter fill(String userId) {
        RateLimiter limiter = RateLimiter.create(CAPACITY, LEAK_RATE, clock);
        for (int i = 0; i < CAPACITY; i++) {
            limiter = limiter.allowRequest(userId, T0).limiter();
        }
        return limiter;
    }
}
