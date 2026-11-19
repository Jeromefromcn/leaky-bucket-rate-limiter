package com.example.ratelimiter.web;

import com.example.ratelimiter.MutableClock;
import com.example.ratelimiter.config.RateLimiterProperties;
import com.example.ratelimiter.service.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RateLimitControllerTest {

    private static final double T0 = 1_700_000_000.0;

    private MutableClock clock;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.ofEpochSecond(1_700_000_000L));
        mockMvc = mockMvc(5, 1.0);
    }

    private MockMvc mockMvc(int capacity, double leakRate) {
        RateLimiterService service = new RateLimiterService(
                new RateLimiterProperties(capacity, leakRate), clock);
        return MockMvcBuilders.standaloneSetup(new RateLimitController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void firstRequestIsAllowed() throws Exception {
        mockMvc.perform(post("/api/rate-limit/{userId}", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.bucket.userId").value("user-1"))
                .andExpect(jsonPath("$.bucket.level").value(1.0));
    }

    @Test
    void burstBeyondCapacityReturns429WithRetryAfter() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/rate-limit/{userId}", "user-1"))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/api/rate-limit/{userId}", "user-1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(jsonPath("$.allowed").value(false));
    }

    @Test
    void retryAfterUsesEffectiveLevelAndLeakRate() throws Exception {
        mockMvc = mockMvc(5, 0.3);
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/rate-limit/{userId}", "user-1"))
                    .andExpect(status().isOk());
        }
        clock.setEpochSeconds(T0 + 1.0);

        // effective level 4.7, required decay 0.7, ceil(0.7 / 0.3) = 3
        mockMvc.perform(post("/api/rate-limit/{userId}", "user-1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "3"));
    }

    @Test
    void noRetryAfterWhenLeakRateIsZero() throws Exception {
        mockMvc = mockMvc(1, 0.0);
        mockMvc.perform(post("/api/rate-limit/{userId}", "user-1"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/rate-limit/{userId}", "user-1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void knownUserStateIsReturned() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/rate-limit/{userId}", "user-1"));
        }

        mockMvc.perform(get("/api/rate-limit/{userId}", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("user-1"))
                .andExpect(jsonPath("$.level").value(5.0))
                .andExpect(jsonPath("$.remaining").value(0.0));
    }

    @Test
    void fullyLeakedBucketStillReturns200() throws Exception {
        mockMvc.perform(post("/api/rate-limit/{userId}", "user-1"));
        clock.setEpochSeconds(T0 + 10);

        mockMvc.perform(get("/api/rate-limit/{userId}", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value(0.0))
                .andExpect(jsonPath("$.remaining").value(5.0));
    }

    @Test
    void unknownUserReturns404() throws Exception {
        mockMvc.perform(get("/api/rate-limit/{userId}", "nobody"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void blankUserIdReturns400() throws Exception {
        mockMvc.perform(get("/api/rate-limit/{userId}", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_user_id"));
    }
}
