package com.seatbook.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class IdempotencyRepository {

    public record Entry(String requestHash, UUID reservationId) {}

    private static final String FIND =
            "SELECT request_hash, reservation_id FROM idempotency_keys WHERE user_id = :userId AND key = :key AND show_id = :showId";

    private static final String INSERT = """
            INSERT INTO idempotency_keys (user_id, key, request_hash, reservation_id, show_id)
            VALUES (:userId, :key, :hash, :rid, :showId)
            """;
    private static final String LOCK =
            "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:k, 0))) t";

    private final NamedParameterJdbcTemplate jdbc;

    public IdempotencyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Entry> find(String userId, String key, UUID showId) {
        return jdbc.query(FIND,
                        new MapSqlParameterSource().addValue("userId", userId).addValue("key", key).addValue("showId", showId),
                        (rs, n) -> new Entry(rs.getString("request_hash"), rs.getObject("reservation_id", UUID.class)))
                .stream().findFirst();
    }

    public void insert(String userId, String key, String hash, UUID reservationId, UUID showId) {
        jdbc.update(INSERT, new MapSqlParameterSource()
                .addValue("userId", userId).addValue("key", key).addValue("showId", showId)
                .addValue("hash", hash).addValue("rid", reservationId));
    }

    public void lock(String userId, UUID showId, String key) {
        jdbc.queryForObject(LOCK,
                new MapSqlParameterSource("k", userId + ":" + showId + ":" + key), Integer.class);
    }
}
