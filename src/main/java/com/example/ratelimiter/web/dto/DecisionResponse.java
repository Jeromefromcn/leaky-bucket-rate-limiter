package com.example.ratelimiter.web.dto;

public record DecisionResponse(boolean allowed, BucketStateResponse bucket) {
}
