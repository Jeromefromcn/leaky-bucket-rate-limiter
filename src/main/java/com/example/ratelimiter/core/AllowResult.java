package com.example.ratelimiter.core;

/**
 * Outcome of a single {@link RateLimiter#allowRequest(String, double)} call.
 *
 * @param allowed whether the request may be executed
 * @param limiter the limiter carrying the resulting state; on rejection this is
 *                the same instance that was passed in
 */
public record AllowResult(boolean allowed, RateLimiter limiter) {
}
