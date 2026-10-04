package com.seatbook.repository;

import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public class QuotaRepository {

    private static final String ENSURE_ROW = """
        INSERT INTO user_show_quota (show_id, user_id, seat_count)
        VALUES (:showId, :userId, 0)
        ON CONFLICT (show_id, user_id) DO NOTHING
        """;

    // THE per-user-limit decision: only increments if the result stays within the limit
    private static final String TRY_ADD = """
        UPDATE user_show_quota SET seat_count = seat_count + :n
        WHERE show_id = :showId AND user_id = :userId AND seat_count + :n <= :limit
        """;

    private static final String LOCK_ROW =
            "SELECT 1 FROM user_show_quota WHERE show_id = :showId AND user_id = :userId FOR UPDATE";

    private static final String REMOVE = """
        UPDATE user_show_quota SET seat_count = seat_count - :n
        WHERE show_id = :showId AND user_id = :userId
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public QuotaRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void ensureRow(UUID showId, String userId) {
        jdbc.update(ENSURE_ROW, key(showId, userId));
    }

    /** @return true if the user stayed within the limit (row now holds the new count) */
    public boolean tryAdd(UUID showId, String userId, int n, int limit) {
        return jdbc.update(TRY_ADD, key(showId, userId).addValue("n", n).addValue("limit", limit)) == 1;
    }

    public void lockRow(UUID showId, String userId) {
        jdbc.query(LOCK_ROW, key(showId, userId), (RowCallbackHandler) rs -> { });
    }

    public void removeSeats(UUID showId, String userId, int n) {
        jdbc.update(REMOVE, key(showId, userId).addValue("n", n));
    }

    private static MapSqlParameterSource key(UUID showId, String userId) {
        return new MapSqlParameterSource().addValue("showId", showId).addValue("userId", userId);
    }
}
