package com.example.ratelimiter.web.dto;

import com.example.ratelimiter.core.BucketSnapshot;

public record BucketStateResponse(
        String userId,
        double capacity,
        double leakRate,
        double level,
        double remaining,
        double lastUpdated) {

    public static BucketStateResponse from(BucketSnapshot snapshot) {
        return new BucketStateResponse(
                snapshot.userId(),
                snapshot.capacity(),
                snapshot.leakRate(),
                snapshot.level(),
                snapshot.remaining(),
                snapshot.lastUpdated());
    }
}
