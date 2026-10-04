package com.seatbook;

import static com.seatbook.support.Burst.assertNoServerErrors;
import static com.seatbook.support.Burst.assertOnly;
import static com.seatbook.support.Burst.count;
import static com.seatbook.support.Burst.tally;
import static com.seatbook.support.TestClient.labels;
import static org.assertj.core.api.Assertions.assertThat;

import com.seatbook.service.ReservationFacade;
import com.seatbook.support.Burst;
import com.seatbook.support.Invariants;
import com.seatbook.support.Result;
import com.seatbook.support.TestClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ConcurrencyTest extends AbstractIntegrationTest {

    /** Lower this if your machine runs out of sockets during test 13. */
    static final int STAMPEDE = 3000;

    @Autowired JdbcTemplate jdbc;
    @Autowired ReservationFacade facade;

    TestClient api;
    long retriesBefore;

    @BeforeEach
    void setUp() {
        api = new TestClient("http://localhost:" + port);
        retriesBefore = facade.retryCount();
    }

    /** With one global lock order a retry means the ordering is broken, so every test asserts zero. */
    @AfterEach
    void noDeadlockRetries() {
        assertThat(facade.retryCount() - retriesBefore).as("deadlock/lock retries during this test").isZero();
    }

    // ===================================================================================
    // 1. Hot-seat storm: 500 users, one seat -> exactly one winner
    // ===================================================================================
    @RepeatedTest(10)
    void hotSeatStorm_exactlyOneWinner() {
        String show = api.createShow(labels("A", 1, 100));

        List<Result> rs = Burst.run(500, i -> api.reserve("u" + i, show, List.of("A12"), "k" + i));

        assertNoServerErrors(rs);
        assertThat(count(rs, 201)).as(tally(rs).toString()).isEqualTo(1);
        assertThat(count(rs, 409, "seat_taken")).isEqualTo(499);
        assertThat(reservationCount(show)).isEqualTo(1);
        Invariants.check(jdbc, api, show);
    }

    // ===================================================================================
    // 2. Several hot seats, 1000 users spread over them: exactly one winner per seat
    // ===================================================================================
    @Test
    void manyHotSeats_oneWinnerEach() {
        List<String> seats = new ArrayList<>(labels("A", 1, 50));
        seats.addAll(labels("H", 1, 5));
        String show = api.createShow(seats);

        List<Result> rs = Burst.run(1000, i -> api.reserve("u" + i, show, List.of("H" + (i % 5 + 1)), "k" + i));

        assertNoServerErrors(rs);
        for (int h = 0; h < 5; h++) {
            final int hot = h;
            long winners = IntStream.range(0, rs.size())
                    .filter(i -> i % 5 == hot && rs.get(i).status() == 201).count();
            assertThat(winners).as("winners for hot seat H" + (hot + 1)).isEqualTo(1);
        }
        assertOnly(rs, Set.of("201", "409:seat_taken"));
        assertThat(count(rs, 201)).isEqualTo(5);
        Invariants.check(jdbc, api, show);
    }

    // ===================================================================================
    // 3. Pairs asked in opposite orders: no deadlock, exactly one winner per pair
    //    (the service sorts labels, so this also documents that contract)
    // ===================================================================================
    @RepeatedTest(5)
    void oppositeOrderPairs_noDeadlock() {
        int pairs = 100;
        List<String> seats = new ArrayList<>();
        for (int p = 0; p < pairs; p++) {
            seats.add("P" + p + "a");
            seats.add("P" + p + "b");
        }
        String show = api.createShow(seats);

        List<Result> rs = Burst.run(pairs * 4, i -> {
            int p = i / 4;
            String a = "P" + p + "a", b = "P" + p + "b";
            List<String> ask = (i % 2 == 0) ? List.of(a, b) : List.of(b, a);
            return api.reserve("u" + i, show, ask, "k" + i);
        });

        assertNoServerErrors(rs);
        for (int p = 0; p < pairs; p++) {
            final int pair = p;
            long winners = IntStream.range(pair * 4, pair * 4 + 4).filter(i -> rs.get(i).status() == 201).count();
            assertThat(winners).as("winners for pair " + pair).isEqualTo(1);
        }
        assertThat(count(rs, 201)).isEqualTo(pairs);
        Invariants.check(jdbc, api, show);
    }

    // ===================================================================================
    // 4. Random overlapping 2-3 seat requests from a pool of 10: no seat sold twice
    // ===================================================================================
    @RepeatedTest(5)
    void overlappingMultiSeat_noDoubleOwnership() {
        long seed = System.nanoTime();
        Random rnd = new Random(seed);
        int users = 300;
        List<String> pool = labels("S", 1, 10);
        List<List<String>> asks = new ArrayList<>();
        for (int i = 0; i < users; i++) {
            List<String> copy = new ArrayList<>(pool);
            Collections.shuffle(copy, rnd);
            asks.add(new ArrayList<>(copy.subList(0, 2 + rnd.nextInt(2))));
        }
        String show = api.createShow(pool);

        List<Result> rs = Burst.run(users, i -> api.reserve("u" + i, show, asks.get(i), "k" + i));

        assertNoServerErrors(rs);
        assertOnly(rs, Set.of("201", "409:seat_taken"));
        Set<String> owned = new HashSet<>();
        for (int i = 0; i < users; i++) {
            if (rs.get(i).status() == 201) {
                for (String seat : asks.get(i)) {
                    assertThat(owned.add(seat)).as("seat " + seat + " sold twice (seed " + seed + ")").isTrue();
                }
            }
        }
        assertThat(confirmedSeats(show)).isEqualTo(owned.size());
        Invariants.check(jdbc, api, show);
    }

    // ===================================================================================
    // 5. Per-user limit: one user, 10 parallel reserves, limit 4 -> exactly 4 succeed
    // ===================================================================================
    @RepeatedTest(10)
    void perUserLimit_holdsUnderConcurrency() {
        String show = api.createShow(labels("A", 1, 20), 25_000, 4);

        List<Result> rs = Burst.run(10, i -> api.reserve("greedy", show, List.of("A" + (i + 1)), "k" + i));

        assertNoServerErrors(rs);
        assertThat(count(rs, 201)).as(tally(rs).toString()).isEqualTo(4);
        assertThat(count(rs, 409, "per_user_limit")).isEqualTo(6);
        assertThat(quota(show, "greedy")).isEqualTo(4);
        assertThat(confirmedSeats(show)).isEqualTo(4);
        Invariants.check(jdbc, api, show);
    }

    // ===================================================================================
    // 6. Same key, same body, in parallel: one reservation, everyone gets the same answer
    // ===================================================================================
    @RepeatedTest(10)
    void sameKeyInParallel_exactlyOnce() {
        String show = api.createShow(labels("A", 1, 10));

        List<Result> rs = Burst.run(50, i -> api.reserve("alice", show, List.of("A1"), "same-key"));

        assertNoServerErrors(rs);
        assertThat(count(rs, 201)).as(tally(rs).toString()).isEqualTo(50);
        Set<String> ids = rs.stream().map(Result::reservationId).collect(Collectors.toSet());
        assertThat(ids).as("every replay must return the original reservation").hasSize(1);
        assertThat(reservationCount(show)).isEqualTo(1);
        assertThat(quota(show, "alice")).isEqualTo(1);
        Invariants.check(jdbc, api, show);
    }

    // ===================================================================================
    // 7. Same key, different body: 409 idempotency_conflict, nothing written
    // ===================================================================================
    @Test
    void sameKeyDifferentBody_conflict() {
        String show = api.createShow(labels("A", 1, 10));
        Result first = api.reserve("alice", show, List.of("A1"), "K");
        assertThat(first.status()).isEqualTo(201);

        List<Result> rs = Burst.run(20, i -> api.reserve("alice", show, List.of("A2"), "K"));

        assertNoServerErrors(rs);
        assertThat(count(rs, 409, "idempotency_conflict")).as(tally(rs).toString()).isEqualTo(20);
        assertThat(seatStatus(show, "A2")).isEqualTo("available");
        assertThat(reservationCount(show)).isEqualTo(1);
        Invariants.check(jdbc, api, show);
    }

    // ===================================================================================
    // 8. Identity comes from the token only
    // ===================================================================================
    @Test
    void spoofedIdentity_isIgnored() {
        String show = api.createShow(labels("A", 1, 10));

        Result spoofed = api.reserveRaw("attacker", show,
                "{\"seats\":[\"A1\"],\"idempotency_key\":\"s1\",\"user_id\":\"victim\"}",
                "X-User-Id", "victim");
        assertThat(spoofed.status()).isEqualTo(201);
        assertThat(spoofed.userId()).isEqualTo("attacker");
        assertThat(reservationOwner(spoofed.reservationId())).isEqualTo("attacker");

        Result victims = api.reserve("victim", show, List.of("A2"), "s2");
        assertThat(victims.status()).isEqualTo(201);

        Result cancel = api.cancel("attacker", victims.reservationId());
        assertThat(cancel.status()).isEqualTo(403);
        assertThat(cancel.error()).isEqualTo("forbidden");
        assertThat(reservationStatus(victims.reservationId())).isEqualTo("confirmed");

        Result notAdmin = api.post("/shows", "attacker", "{\"name\":\"x\",\"seats\":[\"A1\"],\"price_paise\":1}");
        assertThat(notAdmin.status()).isEqualTo(403);
        Invariants.check(jdbc, api, show);
    }

    // ===================================================================================
    // 9. Parallel cancels of one reservation: all 200, quota decremented exactly once
    // ===================================================================================
    @RepeatedTest(10)
    void parallelCancels_decrementOnce() {
        String show = api.createShow(labels("A", 1, 10));
        Result r = api.reserve("alice", show, List.of("A1", "A2"), "k1");
        assertThat(r.status()).isEqualTo(201);

        List<Result> rs = Burst.run(20, i -> api.cancel("alice", r.reservationId()));

        assertNoServerErrors(rs);
        assertThat(count(rs, 200)).as(tally(rs).toString()).isEqualTo(20);
        assertThat(quota(show, "alice")).isEqualTo(0);
        assertThat(api.counts(show).available()).isEqualTo(10);
        Invariants.check(jdbc, api, show);
    }

    // ===================================================================================
    // 10. Cancel vs rebook: a stale cancel retry must never free the new owner's seat
    // ===================================================================================
    @RepeatedTest(10)
    void cancelVsRebook_staleCancelNeverFreesNewOwner() {
        String show = api.createShow(labels("A", 1, 5));
        Result r1 = api.reserve("alice", show, List.of("A1"), "k0");
        assertThat(r1.status()).isEqualTo(201);

        // phase 1: owner cancels while 50 others race to take the seat
        List<Result> rs = Burst.run(51, i -> i == 0
                ? api.cancel("alice", r1.reservationId())
                : api.reserve("b" + i, show, List.of("A1"), "k" + i));
        assertNoServerErrors(rs);
        assertThat(rs.get(0).status()).isEqualTo(200);
        List<Result> rebooks = rs.subList(1, rs.size());
        assertThat(count(rebooks, 201)).as("at most one new owner: " + tally(rebooks)).isLessThanOrEqualTo(1);

        Result winner = rebooks.stream().filter(x -> x.status() == 201).findFirst().orElse(null);
        if (winner == null) {
            // all lost because the cancel had not landed yet; the seat is free now, so race again
            List<Result> second = Burst.run(50, i -> api.reserve("c" + i, show, List.of("A1"), "k" + i));
            assertNoServerErrors(second);
            assertThat(count(second, 201)).isEqualTo(1);
            winner = second.stream().filter(x -> x.status() == 201).findFirst().orElseThrow();
        }

        // phase 2: the old owner retries the cancel many times, after the seat was resold
        List<Result> stale = Burst.run(20, i -> api.cancel("alice", r1.reservationId()));
        assertNoServerErrors(stale);
        assertThat(count(stale, 200)).isEqualTo(20);

        assertThat(seatStatus(show, "A1")).isEqualTo("confirmed");
        assertThat(seatOwnerReservation(show, "A1")).isEqualTo(UUID.fromString(winner.reservationId()));
        Invariants.check(jdbc, api, show);
    }

    // ===================================================================================
    // 11. One user reserving and cancelling in 20 threads: no deadlock, no drift
    // ===================================================================================
    @Test
    void sameUserReserveCancelLoops_noDeadlockNoDrift() {
        String show = api.createShow(labels("S", 1, 10), 100, 100);

        List<List<Result>> perThread = Burst.run(20, t -> {
            List<Result> out = new ArrayList<>();
            for (int n = 0; n < 10; n++) {
                String seat = "S" + (1 + ((t + n) % 10));
                Result r = api.reserve("alice", show, List.of(seat), "t" + t + "-" + n);
                out.add(r);
                if (r.status() == 201) out.add(api.cancel("alice", r.reservationId()));
            }
            return out;
        });
        List<Result> all = perThread.stream().flatMap(List::stream).toList();

        assertNoServerErrors(all);
        assertOnly(all, Set.of("200", "201", "409:seat_taken"));
        assertThat(quota(show, "alice")).as("every success was cancelled").isEqualTo(0);
        assertThat(api.counts(show).available()).isEqualTo(10);
        Invariants.check(jdbc, api, show);
    }

    // ===================================================================================
    // 12. The reconciliation invariant must hold DURING the burst, not just after
    // ===================================================================================
    @Test
    void invariantHoldsDuringLoad() throws Exception {
        List<String> pool = labels("S", 1, 20);
        String show = api.createShow(pool);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger polls = new AtomicInteger();
        List<String> violations = Collections.synchronizedList(new ArrayList<>());

        Thread poller = Thread.ofPlatform().daemon().start(() -> {
            while (!stop.get()) {
                try {
                    Result g = api.getShow(show);
                    polls.incrementAndGet();
                    if (g.status() != 200) {
                        violations.add("status " + g.status());
                    } else if (!parse(g).consistent()) {
                        violations.add(g.body().substring(0, Math.min(200, g.body().length())));
                    }
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    violations.add(e.toString());
                }
            }
        });

        Random rnd = new Random(42);
        List<List<String>> asks = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            List<String> copy = new ArrayList<>(pool);
            Collections.shuffle(copy, rnd);
            asks.add(new ArrayList<>(copy.subList(0, 1 + rnd.nextInt(3))));
        }
        List<Result> rs = Burst.run(400, i -> api.reserve("u" + i, show, asks.get(i), "k" + i));

        stop.set(true);
        poller.join(5000);

        assertNoServerErrors(rs);
        assertThat(polls.get()).as("poller must have sampled during the burst").isGreaterThan(5);
        assertThat(violations).as("invariant violations seen while the burst ran").isEmpty();
        Invariants.check(jdbc, api, show);
    }

    private static TestClient.Counts parse(Result g) {
        String b = g.body();
        return new TestClient.Counts(num(b, "total_seats"), num(b, "available"), num(b, "held"), num(b, "confirmed"));
    }

    private static int num(String body, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + field + "\"\\s*:\\s*(\\d+)").matcher(body);
        if (!m.find()) throw new IllegalStateException("missing " + field);
        return Integer.parseInt(m.group(1));
    }

    // ===================================================================================
    // 13. Mixed stampede: hot seats, cold seats, retries, limit-busters, multi-seat, cancels
    // ===================================================================================
    @Test
    void mixedStampede_everythingHolds() {
        List<String> seats = new ArrayList<>();
        seats.addAll(labels("H", 1, 5));
        seats.addAll(labels("C", 0, 499));
        seats.addAll(labels("R", 0, 99));
        seats.addAll(labels("L", 0, STAMPEDE - 1));
        seats.addAll(labels("P", 0, 299));
        String show = api.createShow(seats, 25_000, 4);

        // 300 pre-existing reservations that the storm will cancel
        List<Result> pre = Burst.run(300, k -> api.reserve("c" + k, show, List.of("P" + k), "pk" + k));
        assertNoServerErrors(pre);
        assertThat(count(pre, 201)).isEqualTo(300);

        List<Result> rs = Burst.run(STAMPEDE, i -> switch (i % 6) {
            case 0 -> api.reserve("m" + i, show, List.of("H" + ((i / 6) % 5 + 1)), "k" + i);          // hot seats
            case 1 -> api.reserve("m" + i, show, List.of("C" + (i % 500)), "k" + i);                   // cold, some overlap
            case 2 -> { int n = i % 100;                                                               // retries
                yield api.reserve("r" + n, show, List.of("R" + n), "rk" + n); }
            case 3 -> api.reserve("lb" + (i % 20), show, List.of("L" + i), "lk" + i);                  // limit busters
            case 4 -> api.reserve("m" + i, show, List.of("C" + (i % 500), "C" + ((i + 7) % 500)), "k" + i); // multi-seat
            default -> { int k = (i / 6) % 300;                                                        // cancels
                yield api.cancel("c" + k, pre.get(k).reservationId()); }
        });

        System.out.println("stampede tally: " + tally(rs));
        assertNoServerErrors(rs);
        assertOnly(rs, Set.of("200", "201", "409:seat_taken", "409:per_user_limit"));

        for (int h = 0; h < 5; h++) {
            final int hot = h;
            long winners = IntStream.range(0, rs.size())
                    .filter(i -> i % 6 == 0 && (i / 6) % 5 == hot && rs.get(i).status() == 201).count();
            assertThat(winners).as("winners for hot seat H" + (hot + 1)).isEqualTo(1);
        }
        assertThat(count(rs, 409, "per_user_limit")).as("limit busters must be declined").isGreaterThan(0);
        assertThat(maxQuota(show)).as("no user above the per-show limit").isLessThanOrEqualTo(4);
        assertThat(maxReservationsPerRetryUser(show)).as("retrying users reserve exactly once").isEqualTo(1);
        Invariants.check(jdbc, api, show);
    }

    // ---- SQL helpers -------------------------------------------------------------------
    private long reservationCount(String show) {
        return jdbc.queryForObject("SELECT count(*) FROM reservations WHERE show_id = ?", Long.class,
                UUID.fromString(show));
    }

    private long confirmedSeats(String show) {
        return jdbc.queryForObject("SELECT count(*) FROM seats WHERE show_id = ? AND status = 'confirmed'",
                Long.class, UUID.fromString(show));
    }

    private int quota(String show, String user) {
        List<Integer> rows = jdbc.query("SELECT seat_count FROM user_show_quota WHERE show_id = ? AND user_id = ?",
                (rs, n) -> rs.getInt(1), UUID.fromString(show), user);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    private long maxQuota(String show) {
        return jdbc.queryForObject("SELECT COALESCE(max(seat_count), 0) FROM user_show_quota WHERE show_id = ?",
                Long.class, UUID.fromString(show));
    }

    private long maxReservationsPerRetryUser(String show) {
        return jdbc.queryForObject("""
                SELECT COALESCE(max(c), 0) FROM
                  (SELECT count(*) c FROM reservations WHERE show_id = ? AND user_id LIKE 'r%' GROUP BY user_id) t
                """, Long.class, UUID.fromString(show));
    }

    private String seatStatus(String show, String label) {
        return jdbc.queryForObject("SELECT status FROM seats WHERE show_id = ? AND label = ?", String.class,
                UUID.fromString(show), label);
    }

    private UUID seatOwnerReservation(String show, String label) {
        return jdbc.queryForObject("SELECT reservation_id FROM seats WHERE show_id = ? AND label = ?", UUID.class,
                UUID.fromString(show), label);
    }

    private String reservationOwner(String reservationId) {
        return jdbc.queryForObject("SELECT user_id FROM reservations WHERE id = ?", String.class,
                UUID.fromString(reservationId));
    }

    private String reservationStatus(String reservationId) {
        return jdbc.queryForObject("SELECT status FROM reservations WHERE id = ?", String.class,
                UUID.fromString(reservationId));
    }
}
