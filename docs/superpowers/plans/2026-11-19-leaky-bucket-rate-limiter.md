# Leaky Bucket Rate Limiter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the single global in-memory leaky-bucket rate limiter and REST API described by `backend-4.md`, with an immutable core, a serialized service shell, HTTP 200/429 semantics, and deterministic tests.

**Architecture:** An immutable `RateLimiter` value object holds `capacity`, `leakRate`, an immutable `Map<String, Bucket>` and a `Clock`; state transitions are pure functions with explicit timestamps, except `getBucketState`, which reads the injected clock. `RateLimiterService` holds the current limiter in a `volatile` field, serializes writes with a lock, and uses the same `Clock` bean. A Spring MVC controller maps decisions and snapshots to DTOs and status codes.

**Tech Stack:** Java 25, Spring Boot 4.1.2 (Spring MVC, embedded Tomcat), Maven, JUnit 5 (Jupiter), MockMvc.

**Spec:** `docs/superpowers/specs/2026-11-19-leaky-bucket-rate-limiter-design.md`

## Global Constraints

- Java 25; Maven 3.8.7 or newer; Spring Boot parent `4.1.2`.
- Dependencies: `spring-boot-starter-webmvc`, `spring-boot-starter-validation`, and `spring-boot-starter-test` (test scope). Do not add Lombok, MapStruct, Vavr, or any other dependency.
- In Spring Boot 4, use `spring-boot-starter-webmvc`; `spring-boot-starter-web` is deprecated in favor of it.
- Package root: `com.example.ratelimiter`.
- Every repository artifact is in English: code, comments, Javadoc, test names, assertion messages, README, commit messages.
- One global limiter created at startup; no endpoint creates limiters.
- Defaults: `capacity = 5` (integer, `>= 1`), `leak-rate = 1.0` (finite, `>= 0`). Environment overrides: `RATE_LIMITER_CAPACITY`, `RATE_LIMITER_LEAK_RATE`.
- Timestamps are Unix epoch seconds as `double`. The server clock is authoritative; client-supplied timestamps are never accepted.
- Core validation: `capacity` finite and `>= 1`; `leakRate` finite and `>= 0`; timestamps finite; user IDs non-null in the core and non-blank in the service.
- HTTP paths: `POST /api/rate-limit/{userId}` returns 200 or 429; `GET /api/rate-limit/{userId}` returns 200 or 404; blank user ID returns 400.
- `Retry-After` on 429: `ceil((level - (capacity - 1)) / leakRate)` seconds, minimum 1, using the effective `level` from the response snapshot; omit the header when `leakRate == 0`.
- Do not modify `backend-4.md` or `CLAUDE.md`.
- Do not commit or push to `main`. If commits are authorized, create a feature branch first and never push. If commits are not authorized, skip every commit step and leave the working tree dirty.
- Do not "fix" the documented limitations (O(n) copy, unbounded user map, global write lock, no epsilon) by adding caches, eviction, dependencies, or extra endpoints unless the user asks.

## Review Focus

These are the failure modes most likely to bite a user even though the spec's task list does not cover them. Each has a test in the owning task.

1. A GET concurrent with a write observes a partially updated bucket (level outside `[0, capacity]` or `remaining != capacity - level`). Test: `concurrentReadsSeeConsistentSnapshots` in Task 7.
2. `Retry-After` uses the stored level or assumes `leakRate == 1.0`, producing a wrong whole-second value. Test: `retryAfterUsesEffectiveLevelAndLeakRate` in Task 8.
3. A bucket that has fully leaked to 0 is reported as 404 instead of existing with level 0. Test: `fullyLeakedBucketStillReturns200` in Task 8.
4. Invalid configuration (capacity 0, negative or NaN leak rate) reaches startup instead of failing fast. Tests: `rejectsInvalidConfiguration` in Task 6 and `rejectsInvalidConfigurationAtStartup` in Task 7.
5. `Retry-After` is emitted (or a retry is implied) when `leakRate == 0`, although the bucket can never recover. Test: `noRetryAfterWhenLeakRateIsZero` in Task 8.

## File Structure

```
pom.xml
src/main/java/com/example/ratelimiter/
  RateLimiterApplication.java              Spring Boot entry point, @ConfigurationPropertiesScan
  core/
    Bucket.java                            package-private immutable (level, lastUpdated)
    BucketSnapshot.java                    public immutable query view
    AllowResult.java                       public (allowed, limiter) result
    RateLimiter.java                       immutable limiter, pure transitions, clock-backed state view
  config/
    RateLimiterProperties.java             @ConfigurationProperties(prefix = "rate-limiter"), validated
    TimeConfig.java                        @Bean Clock = Clock.systemUTC()
  service/
    InvalidUserIdException.java            domain error mapped to HTTP 400
    RateLimiterService.java                volatile current, write lock, Decision record
  web/
    RateLimitController.java               POST/GET endpoints, Retry-After
    BucketNotFoundException.java           unknown-user error mapped to HTTP 404
    ApiExceptionHandler.java               @RestControllerAdvice error mapping
    dto/
      BucketStateResponse.java             JSON view of a snapshot
      DecisionResponse.java                JSON view of a decision
      ApiError.java                        {"error", "message"}
src/main/resources/application.yml
src/test/java/com/example/ratelimiter/
  MutableClock.java                        test clock with setEpochSeconds(double)
  core/RateLimiterTest.java
  service/RateLimiterServiceTest.java
  web/RateLimitControllerTest.java
  RateLimiterApplicationTests.java         context load + default configuration
README.md
```

## Preparation (only if commits are authorized)

- [ ] **Step 1: Create a feature branch**

Run: `git switch -c feature/rate-limiter`
Expected: `Switched to a new branch 'feature/rate-limiter'`.

- [ ] **Step 2: Confirm the working tree**

Run: `git status --short`
Expected: only `.gitignore` (untracked) and the design/plan docs are listed; no source files yet.

---

### Task 1: Maven and Spring Boot skeleton

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/example/ratelimiter/RateLimiterApplication.java`
- Create: `src/main/resources/application.yml`
- Existing: `.gitignore` (already ignores `target/`; include it in the first commit if commits are authorized)

**Interfaces:**
- Consumes: nothing.
- Produces: a building Spring Boot application with the Java 25 toolchain and the three starters; later tasks assume `mvn -q test` runs.

- [ ] **Step 1: Write `pom.xml`**

Use `org.springframework.boot:spring-boot-starter-parent:4.1.2`, `java.version` = `25`, group `com.example`, artifact `leaky-bucket-rate-limiter`, version `0.0.1-SNAPSHOT`, and the `spring-boot-maven-plugin`. Dependencies: `spring-boot-starter-webmvc`, `spring-boot-starter-validation`, `spring-boot-starter-test` (scope `test`). No other dependencies.

- [ ] **Step 2: Write `RateLimiterApplication`**

```java
package com.example.ratelimiter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RateLimiterApplication {
    public static void main(String[] args) {
        SpringApplication.run(RateLimiterApplication.class, args);
    }
}
```

- [ ] **Step 3: Write `application.yml`**

```yaml
rate-limiter:
  capacity: ${RATE_LIMITER_CAPACITY:5}
  leak-rate: ${RATE_LIMITER_LEAK_RATE:1.0}
```

- [ ] **Step 4: Verify the skeleton builds**

Run: `mvn -q -DskipTests package`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit (only if authorized)**

```bash
git add pom.xml src/main/java/com/example/ratelimiter/RateLimiterApplication.java src/main/resources/application.yml .gitignore
git commit -m "chore: scaffold Spring Boot application"
```

---

### Task 2: Core value types and the first request

**Files:**
- Create: `src/main/java/com/example/ratelimiter/core/Bucket.java`
- Create: `src/main/java/com/example/ratelimiter/core/BucketSnapshot.java`
- Create: `src/main/java/com/example/ratelimiter/core/AllowResult.java`
- Create: `src/main/java/com/example/ratelimiter/core/RateLimiter.java`
- Create: `src/test/java/com/example/ratelimiter/MutableClock.java`
- Create: `src/test/java/com/example/ratelimiter/core/RateLimiterTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `record Bucket(double level, double lastUpdated)` — package-private.
  - `record BucketSnapshot(String userId, double capacity, double leakRate, double level, double remaining, double lastUpdated)` — public.
  - `record AllowResult(boolean allowed, RateLimiter limiter)` — public.
  - `RateLimiter.create(double capacity, double leakRate)` and `RateLimiter.create(double capacity, double leakRate, Clock clock)` — public static factories.
  - `AllowResult RateLimiter.allowRequest(String userId, double timestamp)`.
  - `Optional<BucketSnapshot> RateLimiter.getBucketState(String userId)`.
  - `double RateLimiter.capacity()`, `double RateLimiter.leakRate()`.
  - `MutableClock(Instant)`, `void setEpochSeconds(double)` — test utility.

- [ ] **Step 1: Write `MutableClock`**

```java
package com.example.ratelimiter;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

public final class MutableClock extends Clock {
    private volatile Instant instant;

    public MutableClock(Instant instant) {
        this.instant = Objects.requireNonNull(instant);
    }

    public void setEpochSeconds(double epochSeconds) {
        long seconds = (long) Math.floor(epochSeconds);
        long nanos = Math.round((epochSeconds - seconds) * 1_000_000_000.0);
        this.instant = Instant.ofEpochSecond(seconds, nanos);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
```

- [ ] **Step 2: Write the failing test**

Create `RateLimiterTest` with these shared fields and setup:

```java
private static final double CAPACITY = 5;
private static final double LEAK_RATE = 1.0;
private static final double T0 = 1_700_000_000.0;
private static final double DELTA = 1e-9;

private MutableClock clock;

@BeforeEach
void setUp() {
    clock = new MutableClock(Instant.ofEpochSecond(1_700_000_000L));
}
```

Add the test:

```java
@Test
void firstRequestFromNewUserIsAllowed() {
    RateLimiter limiter = RateLimiter.create(CAPACITY, LEAK_RATE, clock);

    AllowResult result = limiter.allowRequest("user-1", T0);

    assertTrue(result.allowed());
    assertEquals(1.0, result.limiter().getBucketState("user-1").orElseThrow().level(), DELTA);
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `mvn -q -Dtest=RateLimiterTest#firstRequestFromNewUserIsAllowed test`
Expected: compilation failure because the core types do not exist yet.

- [ ] **Step 4: Implement the minimal core**

- `Bucket`: package-private record with `level` and `lastUpdated`.
- `BucketSnapshot`, `AllowResult`: public records exactly as listed in Interfaces.
- `RateLimiter`: final class; fields `capacity`, `leakRate`, `Map<String, Bucket> buckets`, `Clock clock`; private constructor.
- `create(capacity, leakRate)` delegates to `create(capacity, leakRate, Clock.systemUTC())`.
- `create(capacity, leakRate, clock)` stores the arguments; `buckets = Map.of()`.
- `allowRequest(userId, timestamp)`: `Objects.requireNonNull(userId)`; read the existing bucket (may be null); for this task, always allow, compute `newLevel = (existing == null ? 0 : existing.level()) + 1`, store `new Bucket(newLevel, timestamp)` in a copied map, and return a new limiter. (Capacity checks and leaking arrive in later tasks.)
- `getBucketState(userId)`: `Objects.requireNonNull(userId)`; return empty if no bucket; for this task return `new BucketSnapshot(userId, capacity, leakRate, bucket.level(), 0, bucket.lastUpdated())` (remaining and leaking are completed in Task 4).
- Use `Map.copyOf(next)` for the new map, where `next` is a local `HashMap` copy of the current map.

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -q -Dtest=RateLimiterTest#firstRequestFromNewUserIsAllowed test`
Expected: PASS.

- [ ] **Step 6: Commit (only if authorized)**

```bash
git add src/main/java/com/example/ratelimiter/core src/test/java/com/example/ratelimiter/MutableClock.java src/test/java/com/example/ratelimiter/core/RateLimiterTest.java
git commit -m "feat: add immutable rate limiter core"
```

---

### Task 3: Reject requests that would overflow

**Files:**
- Modify: `src/main/java/com/example/ratelimiter/core/RateLimiter.java`
- Modify: `src/test/java/com/example/ratelimiter/core/RateLimiterTest.java`

**Interfaces:**
- Consumes: Task 2's `RateLimiter`, `AllowResult`, `BucketSnapshot`.
- Produces: `allowRequest` returns `AllowResult(false, this)` when `level + 1 > capacity`; otherwise the Task 2 behaviour.

- [ ] **Step 1: Write the failing test**

```java
@Test
void allowsBurstUpToCapacityThenRejects() {
    RateLimiter limiter = RateLimiter.create(CAPACITY, LEAK_RATE, clock);

    for (int i = 0; i < CAPACITY; i++) {
        AllowResult result = limiter.allowRequest("user-1", T0);
        assertTrue(result.allowed(), "request " + (i + 1) + " should be allowed");
        limiter = result.limiter();
    }

    AllowResult rejected = limiter.allowRequest("user-1", T0);

    assertFalse(rejected.allowed());
    assertSame(limiter, rejected.limiter());
}
```

- [ ] **Step 2: Run the tests to verify the new test fails**

Run: `mvn -q -Dtest=RateLimiterTest test`
Expected: `allowsBurstUpToCapacityThenRejects` FAILS at the sixth request ("expected false but was true"); the first test still passes.

- [ ] **Step 3: Implement the capacity check**

In `allowRequest`, before creating the new bucket, reject when the prospective level would exceed capacity:

```java
double level = bucket == null ? 0 : bucket.level();
if (level + 1 > capacity) {
    return new AllowResult(false, this);
}
```

Keep the accepted path from Task 2.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q -Dtest=RateLimiterTest test`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit (only if authorized)**

```bash
git add src/main/java/com/example/ratelimiter/core/RateLimiter.java src/test/java/com/example/ratelimiter/core/RateLimiterTest.java
git commit -m "feat: reject requests that would overflow the bucket"
```

---

### Task 4: Time-based leaking and effective snapshots

**Files:**
- Modify: `src/main/java/com/example/ratelimiter/core/RateLimiter.java`
- Modify: `src/test/java/com/example/ratelimiter/core/RateLimiterTest.java`

**Interfaces:**
- Consumes: Task 2-3's core API.
- Produces: `allowRequest` applies `level' = max(0, level - leakRate * max(0, t - lastUpdated))`; on acceptance stores `level' + 1` and `lastUpdated = max(t, previousLastUpdated)`. `getBucketState` computes the effective level from the injected clock, sets `remaining = max(0, capacity - level)`, and keeps `lastUpdated` equal to the stored last-accepted timestamp.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void allowsAgainOnceEnoughTimeHasLeaked() {
    RateLimiter full = fill("user-1");

    assertFalse(full.allowRequest("user-1", T0 + 0.5).allowed(),
            "only 0.5 units have leaked, the bucket is still too full");
    assertTrue(full.allowRequest("user-1", T0 + 1.0).allowed());
}

@Test
void snapshotReportsEffectiveLevelAtQueryTime() {
    RateLimiter full = fill("user-1");
    clock.setEpochSeconds(T0 + 2.5);

    BucketSnapshot snapshot = full.getBucketState("user-1").orElseThrow();

    assertEquals("user-1", snapshot.userId());
    assertEquals(CAPACITY, snapshot.capacity(), DELTA);
    assertEquals(LEAK_RATE, snapshot.leakRate(), DELTA);
    assertEquals(2.5, snapshot.level(), DELTA);
    assertEquals(2.5, snapshot.remaining(), DELTA);
    assertEquals(T0, snapshot.lastUpdated(), DELTA);
}

@Test
void levelNeverGoesBelowZero() {
    RateLimiter limiter = RateLimiter.create(CAPACITY, LEAK_RATE, clock)
            .allowRequest("user-1", T0).limiter();
    clock.setEpochSeconds(T0 + 100);

    BucketSnapshot snapshot = limiter.getBucketState("user-1").orElseThrow();
    assertEquals(0.0, snapshot.level(), DELTA);
    assertEquals(5.0, snapshot.remaining(), DELTA);

    for (int i = 0; i < CAPACITY; i++) {
        AllowResult result = limiter.allowRequest("user-1", T0 + 100);
        assertTrue(result.allowed());
        limiter = result.limiter();
    }
    assertFalse(limiter.allowRequest("user-1", T0 + 100).allowed());
}

@Test
void rejectedRequestLeavesStoredStateUntouched() {
    RateLimiter full = fill("user-1");

    assertFalse(full.allowRequest("user-1", T0 + 0.5).allowed());

    clock.setEpochSeconds(T0 + 0.5);
    BucketSnapshot snapshot = full.getBucketState("user-1").orElseThrow();
    assertEquals(4.5, snapshot.level(), DELTA);
    assertEquals(T0, snapshot.lastUpdated(), DELTA);
}

@Test
void backwardsTimestampIsTreatedAsNoElapsedTime() {
    RateLimiter limiter = RateLimiter.create(CAPACITY, LEAK_RATE, clock)
            .allowRequest("user-1", T0 + 10).limiter();

    AllowResult earlier = limiter.allowRequest("user-1", T0 + 5);

    assertTrue(earlier.allowed());
    clock.setEpochSeconds(T0 + 10);
    assertEquals(2.0, earlier.limiter().getBucketState("user-1").orElseThrow().level(), DELTA);
}

@Test
void hugeLeakRateDoesNotProduceNaNOrInfinity() {
    RateLimiter limiter = RateLimiter.create(CAPACITY, 1e308, clock)
            .allowRequest("user-1", T0).limiter();
    clock.setEpochSeconds(T0 + 10);

    BucketSnapshot snapshot = limiter.getBucketState("user-1").orElseThrow();

    assertEquals(0.0, snapshot.level(), DELTA);
    assertEquals(5.0, snapshot.remaining(), DELTA);
}
```

Add the helper:

```java
private RateLimiter fill(String userId) {
    RateLimiter limiter = RateLimiter.create(CAPACITY, LEAK_RATE, clock);
    for (int i = 0; i < CAPACITY; i++) {
        limiter = limiter.allowRequest(userId, T0).limiter();
    }
    return limiter;
}
```

- [ ] **Step 2: Run the tests to verify the new tests fail**

Run: `mvn -q -Dtest=RateLimiterTest test`
Expected: the new leak-related tests FAIL (`allowsAgainOnceEnoughTimeHasLeaked` fails because no leaking occurs; the snapshot tests fail on level/remaining values).

- [ ] **Step 3: Implement leaking**

In `allowRequest`, replace the raw stored level with the decayed level:

```java
double previousLastUpdated = bucket == null ? timestamp : bucket.lastUpdated();
double level = bucket == null
        ? 0
        : Math.max(0, bucket.level() - leakRate * Math.max(0, timestamp - bucket.lastUpdated()));
```

On acceptance, store:

```java
double newLevel = level + 1;
double newLastUpdated = Math.max(timestamp, previousLastUpdated);
```

In `getBucketState`, compute from the clock:

```java
Instant now = clock.instant();
double nowSeconds = now.getEpochSecond() + now.getNano() / 1_000_000_000.0;
double level = Math.max(0, bucket.level() - leakRate * Math.max(0, nowSeconds - bucket.lastUpdated()));
double remaining = Math.max(0, capacity - level);
return Optional.of(new BucketSnapshot(userId, capacity, leakRate, level, remaining, bucket.lastUpdated()));
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q -Dtest=RateLimiterTest test`
Expected: PASS (8 tests: the first two plus the six added here).

- [ ] **Step 5: Commit (only if authorized)**

```bash
git add src/main/java/com/example/ratelimiter/core/RateLimiter.java src/test/java/com/example/ratelimiter/core/RateLimiterTest.java
git commit -m "feat: leak bucket level over time"
```

---

### Task 5: User isolation and degenerate configurations

**Files:**
- Modify: `src/test/java/com/example/ratelimiter/core/RateLimiterTest.java`

**Interfaces:**
- Consumes: Task 2-4's core API.
- Produces: no production API changes; regression tests for per-user isolation, unknown users, and `leakRate = 0`.

- [ ] **Step 1: Write the tests**

```java
@Test
void usersHaveIndependentBuckets() {
    RateLimiter fullForA = fill("a");

    AllowResult forB = fullForA.allowRequest("b", T0);

    assertTrue(forB.allowed());
    assertEquals(1.0, forB.limiter().getBucketState("b").orElseThrow().level(), DELTA);
    assertEquals(5.0, forB.limiter().getBucketState("a").orElseThrow().level(), DELTA);
}

@Test
void unknownUserHasNoBucketState() {
    RateLimiter limiter = RateLimiter.create(CAPACITY, LEAK_RATE, clock);

    assertTrue(limiter.getBucketState("nobody").isEmpty());
}

@Test
void zeroLeakRateNeverFreesCapacity() {
    RateLimiter limiter = RateLimiter.create(2, 0.0, clock)
            .allowRequest("user-1", T0).limiter()
            .allowRequest("user-1", T0).limiter();

    assertFalse(limiter.allowRequest("user-1", T0 + 1_000_000).allowed());
}
```

- [ ] **Step 2: Run the tests**

Run: `mvn -q -Dtest=RateLimiterTest test`
Expected: PASS. These behaviours follow from the map-based state and the decay formula; if any test fails, fix the production code, not the test.

- [ ] **Step 3: Commit (only if authorized)**

```bash
git add src/test/java/com/example/ratelimiter/core/RateLimiterTest.java
git commit -m "test: cover user isolation and zero leak rate"
```

---

### Task 6: Validate configuration and timestamps

**Files:**
- Modify: `src/main/java/com/example/ratelimiter/core/RateLimiter.java`
- Modify: `src/test/java/com/example/ratelimiter/core/RateLimiterTest.java`

**Interfaces:**
- Consumes: Task 2-5's core API.
- Produces: `RateLimiter.create` throws `IllegalArgumentException` when `capacity` is not finite or `< 1`, or `leakRate` is not finite or `< 0`; `allowRequest` and `getBucketState` throw `IllegalArgumentException` for non-finite timestamps and `NullPointerException` for a null user ID.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void rejectsInvalidConfiguration() {
    assertThrows(IllegalArgumentException.class, () -> RateLimiter.create(0, LEAK_RATE, clock));
    assertThrows(IllegalArgumentException.class, () -> RateLimiter.create(-1, LEAK_RATE, clock));
    assertThrows(IllegalArgumentException.class, () -> RateLimiter.create(CAPACITY, -0.1, clock));
    assertThrows(IllegalArgumentException.class, () -> RateLimiter.create(Double.NaN, LEAK_RATE, clock));
    assertThrows(IllegalArgumentException.class,
            () -> RateLimiter.create(CAPACITY, Double.POSITIVE_INFINITY, clock));
}

@Test
void rejectsNonFiniteTimestamp() {
    RateLimiter limiter = RateLimiter.create(CAPACITY, LEAK_RATE, clock);

    assertThrows(IllegalArgumentException.class,
            () -> limiter.allowRequest("user-1", Double.NaN));
    assertThrows(IllegalArgumentException.class,
            () -> limiter.allowRequest("user-1", Double.POSITIVE_INFINITY));
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q -Dtest=RateLimiterTest test`
Expected: both new tests FAIL ("Expected IllegalArgumentException to be thrown").

- [ ] **Step 3: Implement validation**

In the `create(capacity, leakRate, clock)` factory or the private constructor:

```java
if (!Double.isFinite(capacity) || capacity < 1) {
    throw new IllegalArgumentException("capacity must be finite and >= 1");
}
if (!Double.isFinite(leakRate) || leakRate < 0) {
    throw new IllegalArgumentException("leakRate must be finite and >= 0");
}
```

In `allowRequest` and `getBucketState`, reject non-finite timestamps in `allowRequest`:

```java
if (!Double.isFinite(timestamp)) {
    throw new IllegalArgumentException("timestamp must be finite");
}
```

Call `Objects.requireNonNull(userId, "userId")` in both methods.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q -Dtest=RateLimiterTest test`
Expected: PASS (13 tests).

- [ ] **Step 5: Commit (only if authorized)**

```bash
git add src/main/java/com/example/ratelimiter/core/RateLimiter.java src/test/java/com/example/ratelimiter/core/RateLimiterTest.java
git commit -m "feat: validate limiter configuration and timestamps"
```

---

### Task 7: Configuration properties, Clock bean, and service

**Files:**
- Create: `src/main/java/com/example/ratelimiter/config/RateLimiterProperties.java`
- Create: `src/main/java/com/example/ratelimiter/config/TimeConfig.java`
- Create: `src/main/java/com/example/ratelimiter/service/InvalidUserIdException.java`
- Create: `src/main/java/com/example/ratelimiter/service/RateLimiterService.java`
- Create: `src/test/java/com/example/ratelimiter/service/RateLimiterServiceTest.java`

**Interfaces:**
- Consumes: `RateLimiter.create(double, double, Clock)`, `AllowResult`, `BucketSnapshot` from Tasks 2-6.
- Produces:
  - `record RateLimiterProperties(int capacity, double leakRate)` — `@ConfigurationProperties(prefix = "rate-limiter")`, `@Validated`, `@Min(1)` on capacity, `@DecimalMin("0.0")` on leakRate.
  - `RateLimiterService(RateLimiterProperties properties, Clock clock)`.
  - `RateLimiterService.Decision allow(String userId)`.
  - `Optional<BucketSnapshot> RateLimiterService.getBucketState(String userId)`.
  - `InvalidUserIdException extends RuntimeException`.
  - `public record Decision(boolean allowed, BucketSnapshot bucket)` nested in `RateLimiterService`.

- [ ] **Step 1: Write the failing service tests**

```java
class RateLimiterServiceTest {

    private static final double T0 = 1_700_000_000.0;

    private MutableClock clock;
    private RateLimiterService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.ofEpochSecond(1_700_000_000L));
        service = new RateLimiterService(new RateLimiterProperties(5, 1.0), clock);
    }

    @Test
    void allowsExactlyCapacityRequestsUnderConcurrency() throws Exception {
        int threads = 32;
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures = IntStream.range(0, threads)
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
            List<Future<?>> readers = IntStream.range(0, 4)
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
```

Imports: `java.time.Instant`, `java.util.List`, `java.util.Optional` (if needed), `java.util.concurrent.*`, `java.util.concurrent.atomic.AtomicInteger`, `java.util.stream.IntStream`, JUnit assertions.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q -Dtest=RateLimiterServiceTest test`
Expected: compilation failure because `RateLimiterProperties`, `RateLimiterService`, and `InvalidUserIdException` do not exist.

- [ ] **Step 3: Implement the properties and Clock bean**

`RateLimiterProperties`:

```java
@ConfigurationProperties(prefix = "rate-limiter")
@Validated
public record RateLimiterProperties(
        @Min(1) int capacity,
        @DecimalMin(value = "0.0", inclusive = true) double leakRate) {
}
```

`TimeConfig`:

```java
@Configuration
public class TimeConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
```

- [ ] **Step 4: Implement the service**

```java
@Service
public class RateLimiterService {

    private final Clock clock;
    private final Object writeLock = new Object();
    private volatile RateLimiter current;

    public RateLimiterService(RateLimiterProperties properties, Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.current = RateLimiter.create(properties.capacity(), properties.leakRate(), clock);
    }

    public Decision allow(String userId) {
        requireUserId(userId);
        synchronized (writeLock) {
            double now = epochSeconds(clock);
            AllowResult result = current.allowRequest(userId, now);
            current = result.limiter();
            BucketSnapshot snapshot = result.limiter().getBucketState(userId)
                    .orElseThrow(() -> new IllegalStateException(
                            "bucket missing after request for user " + userId));
            return new Decision(result.allowed(), snapshot);
        }
    }

    public Optional<BucketSnapshot> getBucketState(String userId) {
        requireUserId(userId);
        return current.getBucketState(userId);
    }

    private static void requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new InvalidUserIdException("userId must not be blank");
        }
    }

    private static double epochSeconds(Clock clock) {
        Instant instant = clock.instant();
        return instant.getEpochSecond() + instant.getNano() / 1_000_000_000.0;
    }

    public record Decision(boolean allowed, BucketSnapshot bucket) {
    }
}
```

`InvalidUserIdException`:

```java
public class InvalidUserIdException extends RuntimeException {
    public InvalidUserIdException(String message) {
        super(message);
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -q -Dtest=RateLimiterServiceTest test`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit (only if authorized)**

```bash
git add src/main/java/com/example/ratelimiter/config src/main/java/com/example/ratelimiter/service src/test/java/com/example/ratelimiter/service/RateLimiterServiceTest.java
git commit -m "feat: add serialized rate limiter service"
```

---

### Task 8: REST API

**Files:**
- Create: `src/main/java/com/example/ratelimiter/web/dto/BucketStateResponse.java`
- Create: `src/main/java/com/example/ratelimiter/web/dto/DecisionResponse.java`
- Create: `src/main/java/com/example/ratelimiter/web/dto/ApiError.java`
- Create: `src/main/java/com/example/ratelimiter/web/BucketNotFoundException.java`
- Create: `src/main/java/com/example/ratelimiter/web/ApiExceptionHandler.java`
- Create: `src/main/java/com/example/ratelimiter/web/RateLimitController.java`
- Create: `src/test/java/com/example/ratelimiter/web/RateLimitControllerTest.java`

**Interfaces:**
- Consumes: `RateLimiterService` and `RateLimiterService.Decision` from Task 7; `BucketSnapshot` from Task 2.
- Produces:
  - `POST /api/rate-limit/{userId}` → 200 `DecisionResponse(allowed=true, bucket)` or 429 `DecisionResponse(allowed=false, bucket)` + `Retry-After` when `leakRate > 0`.
  - `GET /api/rate-limit/{userId}` → 200 `BucketStateResponse` or 404 `ApiError("not_found", ...)`.
  - 400 `ApiError("invalid_user_id", ...)` for blank user IDs.
  - `BucketStateResponse.from(BucketSnapshot)`.

- [ ] **Step 1: Write the failing controller tests**

```java
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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q -Dtest=RateLimitControllerTest test`
Expected: compilation failure because the web classes do not exist.

- [ ] **Step 3: Implement the DTOs and error handler**

`BucketStateResponse`:

```java
public record BucketStateResponse(
        String userId, double capacity, double leakRate,
        double level, double remaining, double lastUpdated) {

    public static BucketStateResponse from(BucketSnapshot snapshot) {
        return new BucketStateResponse(snapshot.userId(), snapshot.capacity(),
                snapshot.leakRate(), snapshot.level(), snapshot.remaining(), snapshot.lastUpdated());
    }
}
```

`DecisionResponse`:

```java
public record DecisionResponse(boolean allowed, BucketStateResponse bucket) {
}
```

`ApiError`:

```java
public record ApiError(String error, String message) {
}
```

`BucketNotFoundException`:

```java
public class BucketNotFoundException extends RuntimeException {
    public BucketNotFoundException(String userId) {
        super("No bucket exists for user '" + userId + "'");
    }
}
```

`ApiExceptionHandler`:

```java
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(BucketNotFoundException.class)
    ResponseEntity<ApiError> notFound(BucketNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("not_found", exception.getMessage()));
    }

    @ExceptionHandler(InvalidUserIdException.class)
    ResponseEntity<ApiError> invalidUserId(InvalidUserIdException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiError("invalid_user_id", exception.getMessage()));
    }
}
```

- [ ] **Step 4: Implement the controller**

```java
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
        DecisionResponse body = new DecisionResponse(decision.allowed(),
                BucketStateResponse.from(decision.bucket()));

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
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -q -Dtest=RateLimitControllerTest test`
Expected: PASS (8 tests). If `blankUserIdReturns400` fails because MockMvc rejects the space before the handler, use `get("/api/rate-limit/%20")` and keep the same expectation.

- [ ] **Step 6: Commit (only if authorized)**

```bash
git add src/main/java/com/example/ratelimiter/web src/test/java/com/example/ratelimiter/web/RateLimitControllerTest.java
git commit -m "feat: expose rate limiter REST API"
```

---

### Task 9: Application context and default configuration

**Files:**
- Create: `src/test/java/com/example/ratelimiter/RateLimiterApplicationTests.java`

**Interfaces:**
- Consumes: all beans from Tasks 1-8.
- Produces: a context-load test proving the Clock bean, properties binding, and service wiring are valid.

- [ ] **Step 1: Write the context test**

```java
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
```

- [ ] **Step 2: Run the test**

Run: `mvn -q -Dtest=RateLimiterApplicationTests test`
Expected: PASS if the Task 1 `@ConfigurationPropertiesScan` and the Task 7 `Clock` bean are in place. This is an infrastructure smoke test, not a new behaviour, so it may pass immediately; its value is catching wiring regressions. If it fails, continue to Step 3.

- [ ] **Step 3: Fix the wiring if needed**

Ensure `RateLimiterApplication` has `@ConfigurationPropertiesScan` and that `TimeConfig` declares the `Clock` bean. No other production change should be needed.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q -Dtest=RateLimiterApplicationTests test`
Expected: PASS.

- [ ] **Step 5: Commit (only if authorized)**

```bash
git add src/test/java/com/example/ratelimiter/RateLimiterApplicationTests.java
git commit -m "test: verify application context and defaults"
```

---

### Task 10: README

**Files:**
- Create: `README.md`

**Interfaces:**
- Consumes: the final API and configuration from Tasks 1-9.
- Produces: reviewer-facing documentation.

- [ ] **Step 1: Write `README.md`**

Include, in this order:

1. **Overview** — one global in-memory leaky-bucket rate limiter; per-user buckets; meter/policer (not a shaper); bursts up to capacity; overflow rejected immediately.
2. **Run** — `mvn spring-boot:run`; Java 25 and Maven 3.9+ prerequisites; configuration via `RATE_LIMITER_CAPACITY` and `RATE_LIMITER_LEAK_RATE` (defaults 5 and 1.0).
3. **API** — `POST /api/rate-limit/{userId}` (200/429, `Retry-After`), `GET /api/rate-limit/{userId}` (200/404), error bodies; `curl` examples.
4. **Tests** — `mvn test`.
5. **Design decisions and trade-offs**:
   - leaky bucket as a meter; the queue/shaper distinction;
   - equivalence with the token bucket (`tokens = capacity - level`) and why there is no burst beyond capacity;
   - immutable state and write-on-accept; the O(n) map copy and why it is acceptable here;
   - the Clock inside the limiter for the no-timestamp `getBucketState`;
   - server time only, 429 + `Retry-After`, 404/400 semantics;
   - the single global write lock and lock-free reads;
   - known limitations: unbounded map, no eviction, double precision without epsilon, path-variable user IDs, no persistence;
   - production optimization paths: per-user mutable buckets with `ConcurrentHashMap.compute`, sharded locks, eviction, metrics.
6. **Configuration reference** — the two keys, defaults, and environment overrides.

- [ ] **Step 2: Verify the commands in the README**

Run: `mvn -q test`
Expected: BUILD SUCCESS with all tests passing.

- [ ] **Step 3: Commit (only if authorized)**

```bash
git add README.md
git commit -m "docs: add README with usage and design trade-offs"
```

---

### Task 11: Full verification

**Files:**
- None (verification only).

**Interfaces:**
- Consumes: the full application.
- Produces: evidence that the suite and the running app behave as documented.

- [ ] **Step 1: Run the full test suite**

Run: `mvn -q test`
Expected: BUILD SUCCESS; all tests pass, no skipped tests, no warnings that indicate failures.

- [ ] **Step 2: Start the app with deterministic settings**

Run in a separate shell: `RATE_LIMITER_CAPACITY=5 RATE_LIMITER_LEAK_RATE=0 mvn -q spring-boot:run`
Expected: the application starts on port 8080.

- [ ] **Step 3: Exercise the API**

Run:

```bash
for i in 1 2 3 4 5; do curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/api/rate-limit/alice; done
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/api/rate-limit/alice
curl -s http://localhost:8080/api/rate-limit/alice
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/rate-limit/bob
```

Expected: five `200`, then `429`, then a JSON bucket with `level` 5.0, then `404`.

- [ ] **Step 4: Stop the app**

Stop the `spring-boot:run` process (Ctrl-C in its shell). No commit.

---

## Self-Review

- **Spec coverage:** every section of the design maps to a task: scaffold (1), core state (2), overflow (3), leaking and effective snapshots (4), isolation/zero leak (5), validation (6), service/clock/config (7), HTTP (8), context (9), README (10), verification (11).
- **Type consistency:** `RateLimiter.create(double, double, Clock)`, `allowRequest(String, double)`, `getBucketState(String)`, `BucketSnapshot` fields, `RateLimiterService.Decision`, and the DTOs are named identically across tasks.
- **Review Focus tests:** the five risks are covered by the named tests in Tasks 7 and 8, and the extreme-leak test in Task 4.
- **Proportion:** the plan records decisions (exact signatures, values, test assertions) and leaves method bodies to the implementer except for the algorithm and the fixed skeletons above.
