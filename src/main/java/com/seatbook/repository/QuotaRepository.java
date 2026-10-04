package com.seatbook.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public class QuotaRepository {

    private static final String GET = """
            INSERT INTO user_show_quota (show_id, user_id, seat_count)
            VALUES (:showId, :userId, 0) ON CONFLICT DO NOTHING"
            """;

    private static final String ADD = """
            UPDATE user_show_quota SET seat_count = seat_count + :n
            WHERE show_id = :showId AND user_id = :userId AND seat_count + :n <= :limit
        """;

    private static final String REMOVE = """
        UPDATE user_show_quota SET seat_count = seat_count - :delta
        WHERE show_id = :showId AND user_id = :userId
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

    public int addSeats(UUID showId, String userId, int count) {
        return jdbc.update(ADD, params(showId, userId, count));
    }

    public void removeSeats(UUID showId, String userId, int count) {
        jdbc.update(REMOVE, params(showId, userId, count));
    }

    private static MapSqlParameterSource params(UUID showId, String userId, int delta) {
        return new MapSqlParameterSource()
                .addValue("showId", showId).addValue("userId", userId).addValue("delta", delta);
    }
}
