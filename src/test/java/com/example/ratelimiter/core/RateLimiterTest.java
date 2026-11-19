package com.example.ratelimiter.core;

import com.example.ratelimiter.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
