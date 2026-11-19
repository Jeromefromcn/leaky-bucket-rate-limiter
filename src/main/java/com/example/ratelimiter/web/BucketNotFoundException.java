package com.example.ratelimiter.web;

public class BucketNotFoundException extends RuntimeException {

    public BucketNotFoundException(String userId) {
        super("No bucket exists for user '" + userId + "'");
    }
}
