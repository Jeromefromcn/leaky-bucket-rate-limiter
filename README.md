# Leaky Bucket Rate Limiter

A single, global, in-memory leaky-bucket rate limiter exposed as a small REST API.
Each user has an independent bucket: requests add one unit, the bucket leaks at a
constant rate over time, and a request that would overflow the bucket is rejected.

## Overview

This implementation is a **leaky bucket as a meter** (a policer), not a queue-based
shaper. A request is admitted or rejected immediately; nothing is queued or delayed.
A fresh bucket can admit a burst of up to `capacity` requests, after which the
long-run throughput is bounded by `leakRate`.

The meter is mathematically equivalent to a token bucket with
`tokens = capacity - level`, starting with a full token bucket. That is why it
absorbs a burst of `capacity` requests but never more, and why the sustained rate
is `leakRate` requests per second.

## Requirements

- Java 25
- Maven 3.8.7 or newer (tested with 3.8.7)

## Run

```bash
mvn spring-boot:run
```

The application listens on port 8080.

## API

### Check whether a request is allowed

```bash
curl -i -X POST http://localhost:8080/api/rate-limit/alice
```

- Allowed: `200 OK`

  ```json
  {
    "allowed": true,
    "bucket": {
      "userId": "alice",
      "capacity": 5.0,
      "leakRate": 1.0,
      "level": 1.0,
      "remaining": 4.0,
      "lastUpdated": 1763424000.0
    }
  }
  ```

- Rejected: `429 Too Many Requests`, with `Retry-After` in seconds when the bucket
  can recover (`leakRate > 0`)

  ```json
  {
    "allowed": false,
    "bucket": {
      "userId": "alice",
      "capacity": 5.0,
      "leakRate": 1.0,
      "level": 5.0,
      "remaining": 0.0,
      "lastUpdated": 1763424000.0
    }
  }
  ```

### Get bucket state

```bash
curl -i http://localhost:8080/api/rate-limit/alice
```

- Known user: `200 OK` with the bucket object.
- Unknown user: `404 Not Found`

  ```json
  { "error": "not_found", "message": "No bucket exists for user 'nobody'" }
  ```

- Blank user ID: `400 Bad Request`

  ```json
  { "error": "invalid_user_id", "message": "userId must not be blank" }
  ```

`level` is the effective level at query time (after leaking); `lastUpdated` is the
timestamp of the last accepted request. The server clock is authoritative; clients
cannot supply timestamps.

## Tests

```bash
mvn test
```

The suite covers the core meter (bursts, leaking, fractional time, clamping,
independent users, unknown users, backwards timestamps, validation), the serialized
service (concurrent writes and reads), the HTTP contract (200/429/404/400,
`Retry-After`), and the Spring context with default configuration.

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `rate-limiter.capacity` | `5` | Maximum bucket level; integer `>= 1` |
| `rate-limiter.leak-rate` | `1.0` | Units leaked per second; finite `>= 0` |

Environment overrides:

```bash
RATE_LIMITER_CAPACITY=10 RATE_LIMITER_LEAK_RATE=0.5 mvn spring-boot:run
```

Invalid configuration fails at startup.

## Design decisions and trade-offs

### Meter, not shaper

The brief rejects overflowing requests and returns a boolean from `allow_request`;
that is a meter. A queue-based leaky bucket would delay requests at a fixed output
rate instead of rejecting them. The README makes the distinction explicit because
the same name is used for both devices in networking literature.

### Immutable state and write-on-accept

`RateLimiter` is immutable. `allowRequest` returns a new limiter on acceptance and
the same instance on rejection; the service replaces its `volatile` reference inside
a lock. This matches the brief's returned-new-state contract and keeps rejections
side-effect free.

Each accepted request copies the bucket map, O(n) in the number of users. At this
scale that is a deliberate trade-off: the algorithm stays simple, the state is
trivially thread-safe, and tests are deterministic. Production systems would use
per-user mutable buckets with `ConcurrentHashMap.compute` (O(1) per update), a
persistent map, or sharded locks; those alternatives trade the immutability story
for throughput.

### Time

`allowRequest` takes an explicit timestamp and never reads the clock, so the core
transition is deterministic. The brief's `get_bucket_state` has no timestamp, so the
limiter carries an injected `Clock` (system UTC in production, a fixed/mutable clock
in tests) and computes the effective level at query time. The service uses the same
`Clock` bean when it obtains `now` for a request, so there is a single time source.

### Concurrency

The brief says to assume single-threaded execution, but an HTTP server is not.
Writes are serialized with a single lock; reads take a volatile snapshot of the
immutable limiter and are lock-free. The global lock is the simplest correct choice
for a single global limiter; it also makes the state transition atomic.

### HTTP semantics

A rejected request is `429 Too Many Requests`. `Retry-After` is
`ceil((level - (capacity - 1)) / leakRate)` seconds, using the effective level from
the response snapshot; it is omitted when `leakRate == 0`, because the bucket can
never recover. Unknown users are `404`; blank user IDs are `400`; errors share the
`{ "error", "message" }` shape.

## Known limitations

- The user map is unbounded and never evicts fully-leaked buckets. Restarting the
  process resets all buckets; there is no persistence or cluster coordination.
- The O(n) copy per accepted request becomes expensive with many users.
- The single global write lock serializes writes.
- Levels are `double`; there is no epsilon at the capacity boundary, so a rare
  false rejection is theoretically possible.
- User IDs are path variables, so IDs containing `/` are not supported (Tomcat
  rejects encoded slashes by default).
- `getBucketState` reads the clock, so it is not a pure function. The brief asks to
  minimize mutation, not to be pure.

## Production paths not implemented here

- Per-user mutable buckets plus `ConcurrentHashMap.compute`, or a persistent map,
  to remove the O(n) copy.
- Eviction of buckets whose level is 0.
- Sharded or per-user locks for write throughput.
- Metrics for allowed/rejected counts and bucket cardinality.
- A GCRA/TAT representation to avoid floating-point levels.
- Load tests for the chosen data structure.
