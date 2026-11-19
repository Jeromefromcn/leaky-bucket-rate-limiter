# Leaky Bucket Rate Limiter — Design

- Date: 2026-11-19
- Status: Draft for approval
- Reference brief: `backend-4.md`

## 1. Context and intent

This repository implements a take-home programming test: a single global, in-memory
leaky-bucket rate limiter exposed as a small REST API. The result will be reviewed by a
hiring panel, so the artifact should demonstrate senior-level judgment: a small, correct,
well-tested implementation with explicit trade-offs, not a feature-rich framework.

The brief's core interface is pseudocode:

```
create_rate_limiter(capacity, leak_rate) -> limiter
allow_request(limiter, user_id, timestamp) -> [boolean_allowed, new_limiter_state]
get_bucket_state(limiter, user_id) -> bucket_info | null
```

Ambiguities in the brief and the resolutions chosen are recorded in section 10 and
section 14.

## 2. Requirements (from the brief)

- One global limiter created at server startup; no endpoint creates limiters.
- Per-user buckets. A request adds 1 unit; the bucket leaks at `leak_rate` units per
  second; a request that would overflow the bucket is rejected.
- Defaults: `capacity = 5`, `leak_rate = 1.0`; configurable by environment or config.
- REST API exposes two behaviors:
  1. check whether a request is allowed, updating the global limiter state with the
     returned state;
  2. get bucket state for a user.
- Edge cases: first request from a new user; bucket overflow.
- Unit tests for basic functionality, burst handling, time-based leaking, multiple users,
  and an unknown user.
- Constraints: in-memory structures; assume single-threaded execution; timestamps are
  Unix epoch seconds (may be floating point); user IDs are strings; no database, Redis,
  authentication, or distributed support.
- Deliverables: core implementation, REST API, tests, README (run, test, design
  decisions and trade-offs), source in a git repository.
- Project rules: Java 25, Maven, Spring Boot; every repository artifact is in English.

## 3. Goals, non-goals, anti-goals

**Goals**

- Correct meter semantics: bursts up to capacity, sustained throughput bounded by the
  leak rate, overflow rejected immediately.
- A deterministic core that is easy to test; time is controllable.
- Clear HTTP semantics and a consistent error contract.
- Documented trade-offs, especially the O(n) state copy and in-memory growth.

**Non-goals**

- Traffic shaping, queueing, or delaying requests.
- Persistence, Redis, clustering, or multi-node coordination.
- Authentication/authorization, per-IP identity, or dynamic limiter creation.
- Accepting client-supplied timestamps at the HTTP boundary.
- Observability endpoints or dashboards.

**Anti-goals**

- Implementing a shaper (queue plus fixed-rate drain) or a background leak scheduler.
- Blocking callers when the bucket is full.
- Silent unbounded behavior without documenting it.
- Framework ceremony that obscures the core algorithm.

## 4. Constraints and environment

- Java 25 LTS, Maven, Spring Boot 4.1.x (4.1.2 is the current stable line at the time of
  writing). Use `spring-boot-starter-webmvc`; in Spring Boot 4 the older
  `spring-boot-starter-web` is deprecated in favor of it.
- Spring MVC on the embedded Tomcat container.
- `java.time.Clock` is the only time source. Production uses `Clock.systemUTC()`; tests
  inject a fixed clock.
- Timestamps are Unix epoch seconds represented as `double`.
- The brief says "assume single-threaded", but an HTTP server is not. All limiter writes
  are serialized (section 6); reads are lock-free.
- Every artifact (code, comments, Javadoc, tests, README, commit messages) is in English.

## 5. Core design

### 5.1 Types

**`Bucket`** (package-private immutable record)

- Fields: `double level`, `double lastUpdated`.
- `level` is the bucket level immediately after the last **accepted** request.
- `lastUpdated` is that request's timestamp.
- A rejected request neither creates nor modifies a bucket.

**`BucketSnapshot`** (public immutable record)

- Fields: `String userId`, `double capacity`, `double leakRate`, `double level`,
  `double remaining`, `double lastUpdated`.
- `level` is the effective level at query time, after leaking.
- `remaining = max(0, capacity - level)`.
- `lastUpdated` is the stored timestamp of the last accepted request (not the query
  time).

**`AllowResult`** (public record)

- Fields: `boolean allowed`, `RateLimiter limiter`.
- On rejection, `limiter` is the same instance that was passed in.

**`RateLimiter`** (public final immutable class)

- Fields: `double capacity`, `double leakRate`, `Map<String, Bucket> buckets`,
  `Clock clock`.
- `static RateLimiter create(double capacity, double leakRate)` — public factory; uses
  `Clock.systemUTC()`.
- `static RateLimiter create(double capacity, double leakRate, Clock clock)` — public
  factory for infrastructure and tests. It is public because the service lives in a
  different package and constructs the limiter; making it package-private would force
  the service into the core package or require a Spring-aware core configuration.
- `AllowResult allowRequest(String userId, double timestamp)`.
- `Optional<BucketSnapshot> getBucketState(String userId)`.
- `double capacity()`, `double leakRate()` accessors.

State handling:

- `allowRequest` never mutates the input. On acceptance it returns an `AllowResult`
  carrying a new `RateLimiter`; on rejection it returns `AllowResult(false, this)`.
- The private constructor takes ownership of the passed map. All call sites pass
  immutable maps: the factory passes `Map.of()`, and `allowRequest` passes
  `Map.copyOf(next)`. No internal map reference escapes the class, so no additional
  defensive copy is needed.
- The only mutable global state lives in the service layer (section 6).

### 5.2 Leak math

```
elapsed = max(0, t - lastUpdated)
level'  = max(0, level - leakRate * elapsed)
allowed = level' + 1 <= capacity
```

- Clamping `elapsed` at 0 prevents a backwards clock (NTP adjustment, VM migration, or a
  decreasing timestamp argument) from adding units to the bucket.
- Clamping `level'` at 0 prevents a negative level, which would act as hidden credit and
  allow bursts larger than capacity.
- On acceptance, store `level' + 1` and `lastUpdated = max(t, previousLastUpdated)`. The
  `max` prevents later over-decay if timestamps move backwards.
- A new user starts at `level = 0` with `lastUpdated = t`. Because configuration
  validates `capacity >= 1`, the first request is always accepted.
- No epsilon is applied at the capacity boundary. This is a documented limitation
  (section 11).
- `leakRate = 0` is valid: the bucket never frees capacity once full.

### 5.3 `getBucketState` and the time source

The brief's `get_bucket_state(limiter, user_id)` has no timestamp, while "current bucket
information" is interpreted as the effective level at call time. The chosen resolution:

- `RateLimiter` holds a `Clock`; `getBucketState(userId)` reads `now` from it and computes
  the effective level.
- The public `create(capacity, leakRate)` uses `Clock.systemUTC()`; tests use the
  package-private factory with a fixed clock.
- `allowRequest` stays explicit-time and deterministic; it never reads the clock.
- Unknown user: `Optional.empty()` (the brief's `null` translated to the Java idiom); the
  web layer maps it to 404.
- A bucket whose level has fully leaked to 0 still exists and is returned. Existence
  means "has made at least one request"; it is not 404.

Alternatives considered are in section 10.4.

### 5.4 Validation

- `capacity`: finite and `>= 1`. The configuration layer additionally requires an
  integer.
- `leakRate`: finite and `>= 0`.
- `timestamp`: finite; `NaN` and infinities are rejected.
- `userId`: non-null in the core; the service rejects blank IDs with a domain error
  mapped to HTTP 400.
- Invalid configuration fails at startup with `IllegalArgumentException` (wrapped by
  Spring during bean creation).

## 6. Service layer (imperative shell)

`RateLimiterService`:

- Constructor `(RateLimiterProperties properties, Clock clock)` creates
  `current = RateLimiter.create(properties.capacity(), properties.leakRate(), clock)`.
- `private volatile RateLimiter current;` — the only mutable state.
- `private final Object writeLock = new Object();`
- `Decision allow(String userId)`:
  - validate `userId` (non-blank);
  - inside `synchronized (writeLock)`:
    - `double now = epochSeconds(clock)`;
    - `AllowResult result = current.allowRequest(userId, now)`;
    - `current = result.limiter()`;
    - `BucketSnapshot snapshot = result.limiter().getBucketState(userId)`
      (the bucket exists after any request, because the first request is always
      accepted); return `new Decision(result.allowed(), snapshot)`.
- `Optional<BucketSnapshot> getBucketState(String userId)`:
  - validate `userId`;
  - read `current` (volatile) and call `current.getBucketState(userId)`. No lock is
    needed: the limiter is immutable and the reference swap is atomic.
- `public record Decision(boolean allowed, BucketSnapshot bucket)` — service-level return
  type consumed by the web layer.

The same `Clock` bean is injected into both the limiter and the service, so there is a
single time source. `epochSeconds(clock)` is
`clock.instant().getEpochSecond() + clock.instant().getNano() / 1_000_000_000.0` (read the
instant once).

On an allowed request the POST snapshot shows the incremented level; on a rejected
request it shows the effective level at response time (rejections do not write state).

## 7. REST API

Base path: `/api/rate-limit`.

### `POST /api/rate-limit/{userId}`

- Allowed: `200 OK`, body `{ "allowed": true, "bucket": { ... } }`.
- Rejected: `429 Too Many Requests`, body `{ "allowed": false, "bucket": { ... } }`,
  plus a `Retry-After` header.
- Blank `userId`: `400 Bad Request`.

`Retry-After` (seconds):

```
requiredDecay = level - (capacity - 1)
seconds       = ceil(requiredDecay / leakRate)   // minimum 1
```

`level` in this formula is the effective level from the same `BucketSnapshot` returned in
the response body (section 5.3), not the stored level from `Bucket`. The header is
omitted when `leakRate == 0` (no automatic recovery is possible).

### `GET /api/rate-limit/{userId}`

- Known user: `200 OK`, body is the bucket object.
- Unknown user: `404 Not Found`, body `{ "error": "not_found", "message": "..." }`.
- Blank `userId`: `400 Bad Request` with `{ "error": "invalid_user_id", "message": "..." }`.

### Bucket JSON

```
{
  "userId": "user-1",
  "capacity": 5.0,
  "leakRate": 1.0,
  "level": 3.5,
  "remaining": 1.5,
  "lastUpdated": 1763424000.0
}
```

`level` is the effective level at query time; `lastUpdated` is the last accepted
request's timestamp.

### Error JSON

```
{ "error": "not_found", "message": "No bucket exists for user 'nobody'" }
```

`error` is a stable machine-readable code; `message` is for humans.

### Notes

- The server clock is authoritative; client timestamps are not accepted.
- User IDs are path variables, so IDs containing `/` are unsupported (Tomcat rejects
  percent-encoded slashes by default). This is documented in the README.
- The POST response includes the bucket so the caller can observe the effect of the
  decision; GET is the debugging view. The brief does not prescribe a body shape.
- Responses are JSON (`application/json`).

## 8. Configuration

`src/main/resources/application.yml`:

```yaml
rate-limiter:
  capacity: ${RATE_LIMITER_CAPACITY:5}
  leak-rate: ${RATE_LIMITER_LEAK_RATE:1.0}
```

`RateLimiterProperties` (`@ConfigurationProperties(prefix = "rate-limiter")`, validated):

- `capacity`: integer, `>= 1`.
- `leakRate`: finite, `>= 0`.

The application fails fast on invalid values. The global limiter is created once in the
service constructor, so it is shared by every request.

## 9. Testing strategy

### Core (`RateLimiterTest`, fixed `Clock`)

- first request from a new user is allowed and level becomes 1;
- burst: `capacity` requests allowed at `t = 0`; the next rejected; rejection returns the
  same limiter instance;
- leaking: 0.5 s is not enough, 1.0 s is; fractional levels are computed correctly;
- level clamps at 0; a long idle period restores the full burst;
- users have independent buckets;
- unknown user returns empty;
- `leakRate = 0` never recovers;
- a backwards timestamp adds no units and does not cause later over-decay;
- a rejected request leaves stored level and `lastUpdated` untouched;
- invalid configuration and non-finite timestamps are rejected;
- `getBucketState` uses the injected clock and reports the effective level and remaining
  capacity.

### Service (`RateLimiterServiceTest`)

- concurrency: N threads hit the same user with a fixed clock; exactly `capacity`
  requests are allowed and the rest are rejected;
- blank user ID is rejected with the domain error.

### Web (`RateLimitControllerTest`)

Standalone MockMvc with a real service and a fixed `Clock`:

- POST 200 for the first request; POST 429 after capacity; `Retry-After` present on 429;
- GET 200 for a known user, with a level that reflects the fixed clock;
- GET 404 for an unknown user;
- blank user ID → 400 (via an encoded blank path segment or equivalent).

### README

- prerequisites;
- how to run (`./mvnw spring-boot:run` if the wrapper is added, otherwise
  `mvn spring-boot:run`);
- how to test (`./mvnw test` or `mvn test`);
- configuration defaults and environment overrides;
- design decisions and trade-offs: meter vs shaper, token-bucket equivalence, immutable
  state and O(n) copy, Clock placement, 429 semantics, single global write lock, known
  limitations, and optimization paths.

## 10. Approaches considered

### 10.1 Meter vs queue/shaper (chosen: meter)

A queue-based leaky bucket delays requests at a fixed output rate; overflow means a full
queue. The brief says an overflowing request "should be rejected" and `allow_request`
returns a boolean, so the meter/policer reading is correct. The README documents the
distinction and the mathematical equivalence with a token bucket
(`tokens = capacity - level`), so a reviewer's "isn't this a token bucket?" question has a
prepared answer.

### 10.2 Immutable state vs mutable per-user buckets (chosen: immutable state)

- Immutable `RateLimiter` plus a copy-on-write map makes the state-passing contract
  explicit and tests deterministic. Cost: O(n) per accepted request.
- Mutable `Bucket` objects in a `HashMap` or `ConcurrentHashMap` are O(1) but violate the
  "minimize mutation" direction and make the returned-new-state contract fictitious.
- Production optimization path (documented, not implemented): per-user mutable buckets
  with `ConcurrentHashMap.compute`, or a persistent map, plus sharded locks.

### 10.3 Write-through vs write-on-accept (chosen: write-on-accept)

- Only accepted requests produce a new state; rejections return the same instance. This
  minimizes mutation, avoids map copies under overload (when most requests are
  rejected), and makes "a rejection has no side effect" explicit.
- Write-through (persisting the decayed level even on rejection) keeps stored state
  marginally fresher but adds a write path and the risk of updating level without
  updating `lastUpdated`.

### 10.4 Time source for `getBucketState` (chosen: Clock in the limiter)

- (a) A `Clock` inside `RateLimiter`, defaulting to system UTC and injectable: matches
  the brief's no-timestamp signature and keeps tests deterministic. **Chosen.**
- (b) An explicit timestamp parameter (overload or shell-only): purer, but deviates from
  the brief; deferred by the user.
- (c) A direct `Instant.now()` call in the core: simplest, but untestable and hides a
  side effect.
- (d) A background scheduler that materializes levels continuously: fresh state, but adds
  a thread, timing flakiness, and conflicts with the single-threaded/in-memory spirit.

### 10.5 HTTP rejection semantics (chosen: 429)

`429 Too Many Requests` is the idiomatic status. The body carries the decision and the
bucket, and `Retry-After` tells the caller when to retry. Always returning 200 with
`{ "allowed": false }` was rejected because it hides the rate-limit condition from
generic HTTP clients and monitoring.

### 10.6 Unknown user representation (chosen: Optional/404)

The brief's `null` is pseudocode. `Optional` in the core and 404 at the HTTP boundary are
the idiomatic Java/HTTP representations.

## 11. Trade-offs and known limitations

- O(n) map copy per accepted request, where n is the number of users with buckets.
- The user map is unbounded and never evicts fully-leaked buckets. A production
  implementation would evict entries with level 0 or use a bounded/TTL structure.
- A single global write lock serializes all accepted requests; reads are lock-free.
- The core's `getBucketState` reads the clock, so it is not a pure function. The brief
  asks to minimize mutation, not to be pure.
- Double arithmetic with no epsilon at the capacity boundary; rare false rejections are
  theoretically possible. The API exposes doubles rather than rounding.
- No durability, clustering, or persistence; restarting the process resets all buckets.
- User IDs are path variables; IDs containing `/` are unsupported.
- `Retry-After` is omitted when `leakRate = 0` because no automatic recovery is possible.

## 12. Future work (not in scope)

- Per-user mutable buckets with `ConcurrentHashMap.compute`, or persistent maps, to
  remove O(n) copies.
- Eviction of buckets whose level is 0 (with a documented change to `get_bucket_state`'s
  null semantics).
- Sharded or per-user locking to increase write throughput.
- Metrics/actuator endpoints (allowed/rejected counts, bucket count).
- A GCRA/TAT representation to avoid floating-point levels.
- Load tests and a benchmark of the O(n) copy.

## 13. What is left to the builder

- Package layout and class names (proposed:
  `com.example.ratelimiter.{core,service,web,config}`).
- Maven setup: Spring Boot 4.1.2 parent, Java 25, `spring-boot-starter-webmvc`,
  validation, test; optionally the Maven Wrapper.
- Exact DTO field order and Jackson configuration.
- Javadoc wording and README prose.
- Exact exception classes and `@RestControllerAdvice` mapping.
- Test helper structure and whether to use standalone MockMvc or `@WebMvcTest`.
- Whether to add a context-load smoke test.
- Code formatting and import order.
- Do not commit or push without the user's explicit instruction.

## 14. Decision log (calls made during design)

1. "Current" in `get_bucket_state` means the effective level at call time, not the last
   stored level.
2. The bucket is a meter/policer, not a queue/shaper; bursts up to capacity are allowed
   and executed immediately; overflow is rejected.
3. State is immutable and passed back to the caller; the service replaces its reference
   under a write lock.
4. Rejections do not write state; accepted requests do.
5. The limiter carries a `Clock` for `getBucketState`; `allowRequest` takes an explicit
   timestamp.
6. Unknown users are `Optional.empty()` in the core and 404 at the HTTP boundary.
7. Rejected requests return HTTP 429 with `Retry-After` when recoverable.
8. No epsilon at the capacity boundary; documented as a limitation.
9. No bucket eviction; documented as a limitation and future optimization.
10. `capacity` is validated as finite and `>= 1` in the core, and as an integer `>= 1` in
    configuration.
11. The service returns a `Decision` containing both the boolean and the snapshot; the
    POST body is `{ "allowed": ..., "bucket": ... }`.
12. The same `Clock` bean is used by the limiter and the service; the service's
    post-decision snapshot is read at response time.
13. The Clock-taking `RateLimiter` factory is public because the service constructs the
    limiter from a different package. `Decision` is public because the web layer consumes
    it.
14. `Retry-After` is computed from the effective level in the response snapshot, not the
    stored level.
