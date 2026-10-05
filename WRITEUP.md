# Seatbook: write-up

**Live URL:** https://seatbook-service.onrender.com

**Repo:** https://github.com/ajinkyatanksale/seatbook-service

**Burst:** https://github.com/ajinkyatanksale/seatbook-service/blob/master/Makefile  

Seatbook is a JSON API for selling assigned seats. It is built with Java 21, Spring Boot, plain `JdbcTemplate` (no JPA), Flyway and a single Postgres instance. Request handling runs on virtual threads in front of a bounded HikariCP pool.

The design rests on one idea: **the database makes every correctness decision. The application only orders the statements and keeps the queue bounded.** Everything below follows from that.

---

## 1. The atomic decision

### Mechanism

A reservation is one database transaction. Three layers each prevent a different failure.

| Layer | What it is | What it prevents |
|---|---|---|
| Row locks | `SELECT label, status FROM seats WHERE show_id = ? AND label IN (...) ORDER BY label FOR UPDATE` | Two transactions deciding about the same seat at once. This is where the decision is made |
| Guarded update | `UPDATE seats SET status='confirmed', reservation_id=? WHERE show_id=? AND label IN (...) AND status='available'`, followed by an assertion that the row count equals the number of seats requested | Anything that slips past the locks (a bug, a future code path). If it fires, the code throws, because it should be impossible |
| Constraints | `PRIMARY KEY (show_id, label)`, and `CHECK ((status = 'available') = (reservation_id IS NULL))` | A seat existing twice, or a seat that is "confirmed to nobody" |

### Why it is race-free

The service runs at the default READ COMMITTED isolation level. Take 500 users all asking for seat `A12`:

1. All 500 transactions reach `SELECT ... FOR UPDATE` on the same row.
2. Postgres lets one acquire the row lock. The other 499 wait.
3. The winner sees `available`, inserts the reservation, updates the seat to `confirmed` and commits.
4. As each waiter's lock is released, it **re-reads the latest committed version of the row**. It sees `confirmed`, so it throws `seat_taken` (409) and its transaction rolls back.

Exactly one transaction ever sees `available`, because the lock turns a read-then-write into a single serialized step. The check and the write cannot be separated by another transaction.

### Multi-seat requests and deadlocks

A request for `[A1, A2]` and another for `[A2, A1]` could deadlock if each locked its first seat and waited for the second. The service prevents this with **one global lock order**, used by both `reserve` and `cancel`:

```
1. advisory lock on (user, show, idempotency key)   -- reserve only
2. the user's quota row for this show
3. seat rows, ordered by label
```

A deadlock needs a cycle of transactions each waiting on a lock the next one holds. With a single total order, no transaction waits for something earlier in the order while holding something later, so no cycle can form.

This part was discovered while designing for scale. The initial flow written, had different of order of table access, which was susceptible to deadlocks. This was identified and a global lock order was enforced. Also, the database does not lock the rows in the same order we provide in where clause of update. So, an explicit ORDER BY clause added for label to enforce the locking in same order to avoid deadlocks.

### Partial requests

Behaviour is **all-or-nothing**. If a user asks for `[A12, A13]` and A13 is taken, the whole request is declined with `seat_taken` and nothing is held. This works because the decline is an exception thrown inside the transaction, so the rollback undoes the reservation insert and every earlier statement.

### Per-user limit

The limit is enforced with a guarded update, not a `COUNT`:

```sql
UPDATE user_show_quota SET seat_count = seat_count + :n
WHERE show_id = :showId AND user_id = :userId AND seat_count + :n <= :limit
```

Zero rows updated means `per_user_limit` (409). The update holds the quota row lock until commit, so one user's parallel requests for a show are serialized while different users never block each other. A plain `COUNT` followed by an insert would let 10 parallel requests all read 0 and all pass.

### Money

All amounts are `BIGINT` paise. The service computes the total with `Math.multiplyExact`, and the request DTO caps `price_paise`, so overflow cannot produce a corrupt amount. A float in a money field is rejected with 400. A database `CHECK (amount_paise >= 0)` backs this up.

---

## 2. Idempotency

### Where the key lives

The `idempotency_keys` table has the primary key **`(user_id, show_id, key)`** and stores a request hash and the resulting reservation id. The row is written **in the same transaction as the reservation**. Either both exist or neither does.

### How exactly-once is enforced

The first statement of every reserve takes `pg_advisory_xact_lock(hashtextextended('<user>:<show>:<key>', 0))`. Requests with the same user, show and key take turns. The second one runs its lookup only after the first has committed, finds the row, and returns the original reservation.

The alternative was to let both requests proceed and catch the unique-violation on insert. In Postgres a failed statement aborts the entire transaction, so the loser would have to redo all its work. The advisory lock avoids that situation completely. It is released automatically at commit or rollback, so it cannot leak.

### Same key, different body

The request hash is SHA-256 over the show id and the **sorted, length-prefixed** seat labels. Sorting makes `["A2","A1"]` and `["A1","A2"]` the same request. Length-prefixing stops `["A,B"]` from colliding with `["A","B"]`. A stored key with a different hash returns **409 `idempotency_conflict`**.

### Behaviours worth knowing

- A replay returns **201** with the original body, so it is indistinguishable from the first response. Metrics count it separately (`reservations_declined_total{reason="idempotent_replay"}`).
- A **declined** request stores nothing (the rollback removes everything), so a retry with the same key is evaluated afresh. The seat may have been freed.
- Replaying the key of a **cancelled** reservation returns that reservation with `status: "cancelled"`. It does not rebook.
- The key may come from the `Idempotency-Key` header or the body. If both are present and differ, the request gets 400. A missing key gets 400.

### A design bug the tests found

The first version scoped keys to `(user_id, key)`. Because the concurrency tests reuse user names and key patterns across many shows, they got `idempotency_conflict` on the second show. Any test harness that reuses keys across shows would have been rejected the same way. The spec says same key with different **seats** is a conflict, and says nothing about different shows, so the key is now scoped to `(user, show, key)` (migration V2).

### Known gap

Keys are kept forever. In production they would expire (for example after 24 hours), by TTL sweep or by partitioning on date.

---

## 3. Holds and expiry

**Model: cancel-based, with no TTL.** The spec returns `confirmed` directly from reserve, so there is no intermediate hold to expire, and a background sweeper would add a second writer to reason about.

The `held` status exists in the schema and in the show-state counts so the reconciliation invariant is complete, but **nothing sets it** in this implementation.

### Cancel semantics

- **Only the owner may cancel** (403 `forbidden` otherwise).
- **Cancel is idempotent.** Repeating it returns 200 with no side effects. The conditional `UPDATE reservations SET status='cancelled' WHERE id=? AND status='confirmed'` ensures only one concurrent cancel performs the release and quota decrement.
- **A cancel can never take back a seat sold to someone else.** The release statement is `UPDATE seats ... WHERE show_id = ? AND reservation_id = :this_reservation`. A resold seat points at a different reservation id, so the statement cannot match it. 

---

## 4. Consistency vs availability under a partition

**The service chooses consistency.** Postgres is the single system of record, and when it is unreachable the service refuses requests instead of guessing.

- `/readyz` runs `SELECT 1` with a **2-second hard timeout** and returns 503 if it fails. Hikari's own connection timeout is far longer, so a plain query would hang for a minute when the database is down.
- Lock-wait failures and connection failures map to **503** (`service_busy`, `service_unavailable`). These are honest "dependency problem" answers. They are never a generic 500.
- `/healthz` deliberately does **not** check the database (liveness vs readiness). A platform should not restart a healthy process because the database blipped.

### The interesting partition: a client timeout

When a client times out, the commit may or may not have happened. Idempotency is what makes the retry safe: the same key either returns the original reservation or performs it once.

### Multiple app instances

Correctness does not depend on how many instances run, because the database decides. Only the per-instance semaphore and the metrics are local.

---

## 5. Resilience under load

- **Zero 5xx is a design goal**, so every expected outcome is a 4xx domain decline. A global exception handler maps domain exceptions, validation errors, malformed JSON, wrong methods and unknown paths to 4xx. Only a genuine bug produces a 500, and it is logged with a stack trace and returns a generic body.
- **Retries inside the service:** a lock or deadlock failure is retried (up to 10 attempts, random backoff) *outside* the transaction, in a non-transactional facade. A retry counter is exposed, and the test suite asserts it stays at **0**. If lock ordering were broken, a retry would mean a real bug, not noise.
- **Capacity settings:** Tomcat `max-connections` and `accept-count` are raised so a 20,000-request burst is accepted and queued instead of refused. 
- **Declined requests are cheap.** A decline writes no reservation row. The reservation insert happens only after the seats are locked and verified.

---

## 6. Observability: what pages you at 2am

### What exists

| Signal | Detail |
|---|---|
| `/healthz` | Liveness, no dependencies |
| `/readyz` | Real database check, fails closed within about 2s |
| `/metrics` | Prometheus text: `reservations_confirmed_total`, `reservations_declined_total{reason=seat_taken\|per_user_limit\|idempotent_replay\|...}`, `seats_available{show_id}`, booking retries, queue length, Hikari and JVM metrics, request-latency histogram |
| Logs | Structured JSON with a `request_id` on every line (client `X-Request-Id` honoured if safe, otherwise generated, and echoed back in the response) |


Counters are incremented **after the transaction commits**. Incrementing inside the transaction would count a request that later rolls back. The `seats_available` gauge is read from the database on a short interval, not counted in memory, so it cannot drift from reality.

### What I would page on

| Alert | Why |
|---|---|
| Any sustained 5xx rate | The contract says zero. A 5xx means a bug or a dependency failure |
| `booking_retries_total` increasing | The lock order is broken, or something is deadlocking |
| `/readyz` failing or flapping | Database trouble, so the service is refusing traffic by design |
| `booking_queue_length` high or growing, or Hikari pending connections above zero for a sustained period | Capacity: the semaphore queue or the pool is saturated |
| p99 latency regression | Contention or a slow query |
| **Reconciliation mismatch**: `available + held + confirmed != total_seats`, or `/metrics` disagreeing with `GET /shows/{id}` | Correctness. This is the alert that matters most |


---
## 7. Testing

### Bugs identified during testing

- Float money getting accepted due to lack of constraint on jackson to treat it as floating number instead of converting to nearest integer.
- Mistakenly making createShow as read-only transaction causing failed attempts of show creation.
- NPE in groupingBy for trying to get null from map.
- Wrong comparison of token prefix causing it to crash.
- **Idempotency key scope** was too wide.
- **Quota update with a negative delta** failed the `CHECK (seat_count >= 0)`: Postgres checks the proposed insert row before it resolves `ON CONFLICT`. The fix was to split increment and decrement into separate statements.
- **A missing parameter binding** after the idempotency change produced a wall of 500s. The rollback meant nothing was written, which showed the transaction boundary was working.

### Results against the live service

```
python3 burst\\burst_script.py https://seatbook-service.onrender.com 20000
[*] Target: https://seatbook-service.onrender.com
[*] Creating target show (100 seats)...
[*] Created show_id: ab2f40cc-e715-4bf4-a93f-6d25f47f213a
[*] Launching 20000 requests at concurrency=20...

[+] Stampede completed in 401.17s (49.9 req/sec)

================ RESPONSE SUMMARY ================
Total Requests: 20000
HTTP Statuses : {409: 18354, 201: 1646}
201 Confirmed : 1646
409 Declined Breakdown:
   - per_user_limit      : 3046
   - seat_taken          : 15308
5xx Server Errors: 0
Network Failures : 0

================ FINAL RECONCILIATION ============
Show Final Invariant: Available (0) + Held (0) + Confirmed (100) = 100 / 100

>>> RECONCILIATION RESULT: PASSED (ZERO 5xx, CLEAN INVARIANTS) <<<

```

---

## 8. Authentication (simplified on purpose)

The assignment requires that identity comes from the token and never from the body. So:

- `Authorization: Bearer <token>`: the token **is** the user id (an optional `user:` prefix is stripped).
- The admin token comes from the `ADMIN_TOKEN` environment variable and gates `POST /shows`.
- No request DTO has a `user_id` field, and unknown JSON properties are ignored, so a spoofed field has no effect. The token is also the only thing used to decide who may cancel.

This is a stand-in for an identity provider, and anyone can claim to be anyone. The token-to-user step is isolated in one class (`TokenAuthenticator`), so replacing it with real verification touches one place. That was a deliberate scope decision. The assignment tests correctness under load.

---

## 9. AI usage: directed vs decided

I used an AI assistant throughout. This is an honest account of who did what.

**Directed by me**
- Choice of Java and Spring Boot, and the decision to work from a plan with phases and checkpoints.
- Dropping JWT in favour of token-is-identity, and keeping auth minimal.
- Package layout (`controller`, `dto`, `model`, `service`, `repository`), records instead of Lombok, constructor injection.
- Decisions to stay on Spring Boot 4.x and to check behaviour by running things.
- Deployment to Render, the burst script and the README.

**Proposed by the AI, which I reviewed, discussed and adopted**
- The overall approach: Postgres row locks and guarded updates, a cancel model without TTL, all-or-nothing multi-seat.
- The initial schema.
- The Phase 3 hardening (quota guarded update, advisory lock, ordered seat locking, facade with semaphore and retries).
- The structure of the concurrency test suite and the helper classes.

**Written by me with review**
- The foundations: exception model, auth filter, DTOs, repositories, `ShowService` and the show endpoints. I wrote them, and the AI reviewed them and pointed out bugs.

**What I verified myself**
- I ran every checkpoint (`requests.http`), the full concurrency suite, and the burst against the deployed service.
- I diagnosed failures from logs: the stale Docker image, Flyway not running, the negative-delta quota bug, the idempotency scope bug and the missing binding.

**Places the AI was wrong or incomplete, which I caught**
- Early on it claimed that sorting the seat labels prevents deadlocks. That is only true once the locks are taken with an explicit `ORDER BY label ... FOR UPDATE`, and the final design does that.
- It suggested a quota upsert that fails for negative deltas.
- Its first idempotency scope was too wide.

**Learnt From AI and used it to generate**
- Test suits
- Metrics related code

I can explain the reservation transaction statement by statement without the code in front of me.

---

## 10. Known limitations

- `held` is never set, and there is no expiry.
- Idempotency keys are never purged.
- Single Postgres with no replica or failover.
- Authentication is a stand-in.
- No rate limiting or queue fairness for on-sale.
- No dashboards or alert rules in the repo.
- Free-tier Render cold starts.
