package com.example.ratelimiter.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "rate-limiter")
@Validated
public record RateLimiterProperties(
        @Min(1) int capacity,
        @DecimalMin(value = "0.0", inclusive = true) double leakRate) {
}
