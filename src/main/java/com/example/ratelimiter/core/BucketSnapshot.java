package com.example.ratelimiter.core;

/**
 * Point-in-time view of a user's bucket.
 *
 * @param userId      the user the bucket belongs to
 * @param capacity    maximum bucket level
 * @param leakRate    units leaked per second
 * @param level       effective level at the query timestamp
 * @param remaining   units of capacity still available
 * @param lastUpdated timestamp of the last accepted request
 */
public record BucketSnapshot(
        String userId,
        double capacity,
        double leakRate,
        double level,
        double remaining,
        double lastUpdated) {
}
