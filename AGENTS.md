# Problem Statement: Seat-Booking Engine with Zero Double-Booking

> How to use: paste this whole file as your FIRST message to the AI. Then work phase by phase.
> The same file can later be saved in the repo root as AGENTS.md or copilot-instructions.md.

## 0. Who I am and how I want you to work

I am a B.Tech CSE student (graduating 2028) learning backend engineering and system design. I am using you to write code fast, but I must UNDERSTAND every part, because I will defend this project in interviews. So:

1. **Before writing any code,** restate the design in your own words, list your assumptions, and wait for my "go ahead".
2. **Work phase by phase** (Section 9). Never build ahead of the current phase.
3. For every feature, give me: (a) the request flow in 3-6 numbered steps, (b) comments on the WHY of non-obvious lines, (c) one trade-off or alternative you considered, (d) one thing that would break at scale.
4. **Small increments:** one class or one feature at a time. No giant code dumps.
5. **Keep it simple:** no microservices, no Kafka, no reactive code, no extra patterns. Ask before adding any dependency.
6. If I paste my own code, review it first (bugs, layering, naming) and explain issues in simple words.
7. Suggest a commit message after each completed step.

Current phase: **1** (I will update this number).

## 1. The problem

Build the backend of a BookMyShow-style ticketing system. Many users try to book the same seats for the same show at the same moment. The system must guarantee:

- **A seat is never sold twice,** no matter how many concurrent requests, retries, crashes or server instances.
- **Seats can be held for 5 minutes** while the user pays, then confirmed or automatically released.
- **A retried request never creates a second booking** (network retries, double-clicks).

Correctness under concurrency is the whole point of this project. Speed and features come second.

## 2. Tech stack (fixed)

- Java 21, Maven, Spring Boot 3.x (Web, Validation, Data JPA, Actuator)
- PostgreSQL 16 with Flyway migrations
- springdoc-openapi (Swagger UI)
- JUnit 5, Mockito, Testcontainers (PostgreSQL), AssertJ
- k6 for load tests; Docker + docker-compose; GitHub Actions CI
- **No Lombok** (I want to see all the code). Constructor injection only. Java records for DTOs.

## 3. Scope

**In scope:** shows, seat maps, seat holds, confirm, cancel, expiry, idempotency, concurrency tests, load tests, Docker, CI, README, deployed demo.

**Out of scope (do not build):** real payments (simulate with a confirm call), user accounts/login (pass `X-User-Id` header), frontend UI, notifications, microservices, Kafka.

**Stretch (only after everything else is done):** Redis cache for the seat map, a benchmark comparing locking strategies, WebSocket live seat updates, waitlist.

## 4. Domain and state machines

Entities: **Show**, **Seat** (per show), **Booking**, **Reservation** (one row per seat in a booking).

Booking states: `PENDING` -> `CONFIRMED`, `PENDING` -> `EXPIRED`, `PENDING` or `CONFIRMED` -> `CANCELLED`.
Reservation states: `HELD` -> `CONFIRMED`, `HELD` -> `RELEASED`, `CONFIRMED` -> `RELEASED` (on cancel).

Rules:
- Max 6 seats per booking; at least 1; no duplicate seats in one request.
- A hold lasts 5 minutes (configurable via `app.hold-duration`).
- Only `PENDING` bookings whose hold has not expired can be confirmed.
- Expired holds make the seat available again immediately (even before the cleanup job runs).
- Multi-seat holds are **all-or-nothing**: if any seat is unavailable, no seat is held.

## 5. Data model (Flyway V1)

```sql
CREATE TABLE shows (
  id BIGSERIAL PRIMARY KEY,
  title VARCHAR(200) NOT NULL,
  starts_at TIMESTAMPTZ NOT NULL,
  price_per_seat NUMERIC(10,2) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE seats (
  id BIGSERIAL PRIMARY KEY,
  show_id BIGINT NOT NULL REFERENCES shows(id),
  label VARCHAR(10) NOT NULL,            -- e.g. A1
  UNIQUE (show_id, label)
);

CREATE TABLE bookings (
  id UUID PRIMARY KEY,
  show_id BIGINT NOT NULL REFERENCES shows(id),
  user_id VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,           -- PENDING, CONFIRMED, CANCELLED, EXPIRED
  hold_expires_at TIMESTAMPTZ,
  total_amount NUMERIC(10,2) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reservations (
  id BIGSERIAL PRIMARY KEY,
  booking_id UUID NOT NULL REFERENCES bookings(id),
  show_id BIGINT NOT NULL REFERENCES shows(id),
  seat_id BIGINT NOT NULL REFERENCES seats(id),
  status VARCHAR(16) NOT NULL,           -- HELD, CONFIRMED, RELEASED
  hold_expires_at TIMESTAMPTZ
);

-- THE SAFETY NET: at most one ACTIVE reservation per (show, seat).
-- Partial index, so released/expired rows do not block re-booking.
CREATE UNIQUE INDEX uq_active_reservation
  ON reservations (show_id, seat_id)
  WHERE status IN ('HELD', 'CONFIRMED');

CREATE TABLE idempotency_keys (
  idempotency_key VARCHAR(100) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  request_hash CHAR(64) NOT NULL,        -- SHA-256 of the request body
  status VARCHAR(16) NOT NULL,           -- IN_PROGRESS, COMPLETED
  response_status INT,
  response_body JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (idempotency_key, user_id)
);
```

Why a partial unique index instead of a plain UNIQUE(show, seat): a plain one would block re-booking a seat after a cancel or expiry. Explain this in the README.

## 6. API contract

All write endpoints require `X-User-Id` and `Idempotency-Key` headers. All errors use one JSON shape: `{timestamp, status, error, message, path}` from a single `@RestControllerAdvice`.

```
POST /api/v1/shows
  body: { "title", "startsAt", "pricePerSeat", "rows": 10, "seatsPerRow": 10 }
  201: show + generated seats (A1..J10)

GET  /api/v1/shows/{showId}/seats
  200: [{ "seatId", "label", "status": "AVAILABLE|HELD|BOOKED" }]
  (a HELD seat whose hold has expired must show as AVAILABLE)

POST /api/v1/shows/{showId}/holds
  body: { "seatIds": [1, 2, 3] }
  201: { "bookingId", "status": "PENDING", "seats": [...], "holdExpiresAt", "totalAmount" }
  409: one or more seats unavailable (none are held)
  400: invalid input (0 or >6 seats, duplicates)  404: unknown show or seat

POST /api/v1/bookings/{bookingId}/confirm
  200: status CONFIRMED
  410: hold expired    409: booking not in PENDING state

POST /api/v1/bookings/{bookingId}/cancel
  200: status CANCELLED, seats released

GET  /api/v1/bookings/{bookingId}
  200: booking details
```

## 7. Concurrency design (the heart of the project)

1. **The database is the final judge.** Application-level checks ("is the seat free?") are only an optimization. The partial unique index decides who wins a race.
2. **Hold flow, in ONE transaction:**
   - Sort `seatIds` ascending (prevents deadlocks when two requests overlap on several seats).
   - Release stale holds for those seats: `UPDATE reservations SET status='RELEASED' WHERE show_id=? AND seat_id IN (...) AND status='HELD' AND hold_expires_at < now()`.
   - Insert the `HELD` reservations and the `PENDING` booking.
   - A unique-violation (SQLState 23505) means someone else won: roll back everything and return 409.
   - Map deadlock/serialization failures (40P01 / 40001) to a bounded retry (max 3), then 409.
3. **Use the database clock** (`now()`) for expiry decisions, but inject a `Clock` bean in Java code so tests can control time.
4. **Expiry has two layers:** lazy release (step 2 above) plus a `@Scheduled` job every 30 seconds that expires stale `PENDING` bookings and releases their reservations. The job must be safe to run on several instances at once (an idempotent UPDATE, no leader election needed).
5. **Confirm flow:** lock the booking row (`SELECT ... FOR UPDATE`), check state and expiry, set the reservations to `CONFIRMED`, set the booking to `CONFIRMED`.
6. In the README, compare alternatives and say why you chose this one: pessimistic locking (`SELECT ... FOR UPDATE` on seat rows), optimistic locking (version column), and a Redis distributed lock.

## 8. Idempotency design

- Client sends a unique `Idempotency-Key` (UUID) per logical request.
- On each write request, first `INSERT` into `idempotency_keys` with `IN_PROGRESS` (the primary key arbitrates concurrent duplicates).
  - Same key + same request hash, already `COMPLETED`: return the stored response (same status and body), add header `Idempotent-Replay: true`.
  - Same key + different request hash: return 422.
  - Same key currently `IN_PROGRESS`: return 409 "request in progress".
- Store the final response when the operation completes. Delete keys older than 24h (scheduled cleanup).
- The idempotency record and the business change must commit in the SAME transaction.

## 9. Delivery phases (Dec 11 - Jan 7)

- **Phase 1 (Dec 11-17):** project setup, Flyway schema, create show + seats, seat map endpoint, single-threaded hold endpoint, unit tests.
- **Phase 2 (Dec 18-24):** partial unique index, transactional all-or-nothing holds, confirm/cancel, lazy + scheduled expiry, the 1,000-thread race test.
- **Phase 3 (Dec 25-31):** idempotency, error handling, OpenAPI docs, Testcontainers integration tests, Actuator metrics.
- **Phase 4 (Jan 1-7):** Dockerfile + docker-compose, GitHub Actions CI, k6 load tests, README with architecture diagram and decision log, deployed demo.

## 10. Required tests (all must pass in CI)

1. **Race for one seat:** 1,000 threads (start together with a `CountDownLatch`), each with a different user and idempotency key, request the same seat. Assert: exactly 1 success (201), 999 conflicts (409), and the invariant query returns zero rows. Repeat the whole test 10 times.
2. **Idempotent retries:** 1,000 concurrent requests with the SAME idempotency key. Assert: exactly one booking exists and every response is identical.
3. **Overlapping multi-seat requests:** 200 threads each request random pairs from 10 seats. Assert: no seat is held twice, no unhandled deadlock errors, and all-or-nothing holds.
4. **Expiry:** hold a seat, move the injected `Clock` past 5 minutes: the seat shows AVAILABLE, another user can hold it, and confirming the old booking returns 410.
5. **State machine:** every invalid transition returns the right error.

**Invariant query (must always return zero rows):**
```sql
SELECT show_id, seat_id, COUNT(*)
FROM reservations
WHERE status IN ('HELD','CONFIRMED')
GROUP BY show_id, seat_id
HAVING COUNT(*) > 1;
```

## 11. Load tests (k6)

- **Scenario A, read-heavy:** 300 virtual users read the seat map for 2 minutes.
- **Scenario B, contention:** 2,000 virtual users try to hold 100 seats at once. Afterwards, verify with SQL that exactly 100 seats are active and the invariant query returns zero rows.
- Report p50, p95, p99 latency, throughput and error rate in a README table. **Use real numbers from your machine. Never invent numbers.**

## 12. Non-functional requirements

- Configuration via `application.yml` and environment variables (no secrets in the repo).
- Structured logging with a request ID; Actuator health and metrics endpoints.
- Graceful shutdown enabled.
- Layering: Controller -> Service -> Repository. No business logic in controllers.

## 13. Definition of done

- [ ] All tests green in GitHub Actions (badge in README)
- [ ] Invariant query returns zero rows after every load test
- [ ] Live demo URL with Swagger UI
- [ ] README: architecture diagram, API examples, concurrency design, alternatives considered, load-test table, how to run
- [ ] A "Decision log" section in the README answering these (I must be able to explain each):
  1. Why a partial unique index and not application checks?
  2. What happens if the server crashes while a hold is active?
  3. Why sort seat IDs before inserting?
  4. How does idempotency survive concurrent duplicate requests?
  5. What would break first at 100x scale, and what would I change?

## 14. Resume bullet (fill X and Y with MY REAL numbers only)

Designed a seat-booking service (Java, Spring Boot, PostgreSQL) with 5-minute seat holds and idempotent bookings; verified zero double-bookings across X concurrent attempts; sustained Y requests/sec at p99 under Z ms.

## 15. First message back to me

Reply only with: (1) your understanding of the design in 10 lines, (2) assumptions and questions, (3) the Phase 1 task list with the files you will create. Wait for my "go ahead" before writing code.
