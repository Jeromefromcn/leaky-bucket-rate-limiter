package com.example.ratelimiter;

import com.example.ratelimiter.config.RateLimiterProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class RateLimiterApplicationTests {

    @Autowired
    private RateLimiterProperties properties;

    @Test
    void contextLoadsWithDefaultConfiguration() {
        assertEquals(5, properties.capacity());
        assertEquals(1.0, properties.leakRate());
    }
}
