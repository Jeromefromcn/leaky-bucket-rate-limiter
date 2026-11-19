package com.example.ratelimiter.service;

import com.example.ratelimiter.MutableClock;
import com.example.ratelimiter.config.RateLimiterProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterServiceTest {

    private static final double T0 = 1_700_000_000.0;

    private MutableClock clock;
    private RateLimiterService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.ofEpochSecond((long) T0));
        service = new RateLimiterService(new RateLimiterProperties(5, 1.0), clock);
    }

    @Test
    void allowsExactlyCapacityRequestsUnderConcurrency() throws Exception {
        int threads = 32;
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> executor.submit(() -> {
                        start.await();
                        if (service.allow("user-1").allowed()) {
                            allowed.incrementAndGet();
                        }
                        return null;
                    }))
                    .toList();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        }

        assertEquals(5, allowed.get());
    }

    @Test
    void rejectsBlankUserId() {
        assertThrows(InvalidUserIdException.class, () -> service.allow(" "));
        assertThrows(InvalidUserIdException.class, () -> service.getBucketState(""));
    }

    @Test
    void returnsEmptyStateForUnknownUser() {
        assertTrue(service.getBucketState("nobody").isEmpty());
    }

    @Test
    void rejectsInvalidConfigurationAtStartup() {
        assertThrows(IllegalArgumentException.class,
                () -> new RateLimiterService(new RateLimiterProperties(0, 1.0), clock));
    }

    @Test
    void concurrentReadsSeeConsistentSnapshots() throws Exception {
        ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();
        int iterations = 200;

        try (ExecutorService executor = Executors.newFixedThreadPool(5)) {
            Future<?> writer = executor.submit(() -> {
                for (int i = 0; i < iterations; i++) {
                    service.allow("user-1");
                }
            });
            var readers = IntStream.range(0, 4)
                    .mapToObj(i -> executor.submit(() -> {
                        for (int j = 0; j < iterations; j++) {
                            service.getBucketState("user-1").ifPresent(snapshot -> {
                                double level = snapshot.level();
                                if (level < 0 || level > 5 + 1e-9) {
                                    failures.add("level out of range: " + level);
                                }
                                if (Math.abs(snapshot.remaining() - (5 - level)) > 1e-9) {
                                    failures.add("inconsistent remaining: " + snapshot);
                                }
                            });
                        }
                        return null;
                    }))
                    .toList();
            writer.get(5, TimeUnit.SECONDS);
            for (Future<?> reader : readers) {
                reader.get(5, TimeUnit.SECONDS);
            }
        }

        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
