package com.seatbook.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Database-level consistency checks. Run after every test: the API can look right while the tables are wrong. */
public final class Invariants {

    private Invariants() {}

    public static void check(JdbcTemplate jdbc, TestClient api, String showId) {
        UUID id = UUID.fromString(showId);

        assertZero(jdbc, "seat owned by a non-confirmed reservation, or by one from another show", """
                SELECT count(*) FROM seats s JOIN reservations r ON r.id = s.reservation_id
                WHERE s.show_id = ? AND (r.status <> 'confirmed' OR r.show_id <> s.show_id)
                """, id);

        assertZero(jdbc, "quota row differs from the seats the user actually owns", """
                SELECT count(*) FROM user_show_quota q
                WHERE q.show_id = ? AND q.seat_count <>
                  (SELECT count(*) FROM seats s JOIN reservations r ON r.id = s.reservation_id
                   WHERE r.show_id = q.show_id AND r.user_id = q.user_id)
                """, id);

        assertZero(jdbc, "user owns seats but has no quota row", """
                SELECT count(*) FROM
                  (SELECT r.user_id FROM seats s JOIN reservations r ON r.id = s.reservation_id
                   WHERE s.show_id = ? GROUP BY r.user_id) o
                WHERE NOT EXISTS (SELECT 1 FROM user_show_quota q WHERE q.show_id = ? AND q.user_id = o.user_id)
                """, id, id);

        assertZero(jdbc, "confirmed seats differ from the sum over confirmed reservations", """
                SELECT (SELECT count(*) FROM seats WHERE show_id = ? AND status = 'confirmed')
                     - COALESCE((SELECT sum(cardinality(seats)) FROM reservations
                                 WHERE show_id = ? AND status = 'confirmed'), 0)
                """, id, id);

        assertZero(jdbc, "reservation snapshot size differs from the seats it owns", """
                SELECT count(*) FROM reservations r
                WHERE r.show_id = ? AND r.status = 'confirmed'
                  AND cardinality(r.seats) <> (SELECT count(*) FROM seats s
                                               WHERE s.show_id = r.show_id AND s.reservation_id = r.id)
                """, id);

        TestClient.Counts c = api.counts(showId);
        assertThat(c.consistent()).as("available + held + confirmed == total_seats, got " + c).isTrue();
    }

    private static void assertZero(JdbcTemplate jdbc, String what, String sql, Object... params) {
        Long n = jdbc.queryForObject(sql, Long.class, params);
        assertThat(n).as(what).isZero();
    }
}
