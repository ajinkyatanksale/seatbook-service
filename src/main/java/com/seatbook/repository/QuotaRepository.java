package com.seatbook.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public class QuotaRepository {

    private static final String GET =
            "SELECT seat_count FROM user_show_quota WHERE show_id = :showId AND user_id = :userId";

    private static final String UPSERT = """
            INSERT INTO user_show_quota (show_id, user_id, seat_count)
            VALUES (:showId, :userId, :delta)
            ON CONFLICT (show_id, user_id)
            DO UPDATE SET seat_count = user_show_quota.seat_count + :delta
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public QuotaRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int getCount(UUID showId, String userId) {
        List<Integer> rows = jdbc.query(GET,
                new MapSqlParameterSource().addValue("showId", showId).addValue("userId", userId),
                (rs, n) -> rs.getInt("seat_count"));
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    public void addSeats(UUID showId, String userId, int delta) {
        jdbc.update(UPSERT, new MapSqlParameterSource()
                .addValue("showId", showId).addValue("userId", userId).addValue("delta", delta));
    }
}
