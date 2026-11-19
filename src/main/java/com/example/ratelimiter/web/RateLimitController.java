package com.example.ratelimiter.web;

import com.example.ratelimiter.core.BucketSnapshot;
import com.example.ratelimiter.service.RateLimiterService;
import com.example.ratelimiter.web.dto.BucketStateResponse;
import com.example.ratelimiter.web.dto.DecisionResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rate-limit")
public class RateLimitController {

    private final RateLimiterService service;

    public RateLimitController(RateLimiterService service) {
        this.service = service;
    }

    @PostMapping("/{userId}")
    public ResponseEntity<DecisionResponse> check(@PathVariable String userId) {
        RateLimiterService.Decision decision = service.allow(userId);
        DecisionResponse body = new DecisionResponse(
                decision.allowed(), BucketStateResponse.from(decision.bucket()));

        if (decision.allowed()) {
            return ResponseEntity.ok(body);
        }

        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS);
        long retryAfterSeconds = retryAfterSeconds(decision.bucket());
        if (retryAfterSeconds > 0) {
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        }
        return response.body(body);
    }

    @GetMapping("/{userId}")
    public BucketStateResponse state(@PathVariable String userId) {
        return service.getBucketState(userId)
                .map(BucketStateResponse::from)
                .orElseThrow(() -> new BucketNotFoundException(userId));
    }

    private static long retryAfterSeconds(BucketSnapshot bucket) {
        if (bucket.leakRate() <= 0) {
            return 0;
        }
        double requiredDecay = bucket.level() - (bucket.capacity() - 1);
        if (requiredDecay <= 0) {
            return 0;
        }
        return Math.max(1, (long) Math.ceil(requiredDecay / bucket.leakRate()));
    }
}
