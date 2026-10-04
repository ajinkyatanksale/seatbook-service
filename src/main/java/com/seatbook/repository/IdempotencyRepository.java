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
            "SELECT request_hash, reservation_id FROM idempotency_keys WHERE user_id = :userId AND key = :key";

    private static final String INSERT = """
            INSERT INTO idempotency_keys (user_id, key, request_hash, reservation_id)
            VALUES (:userId, :key, :hash, :rid)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public IdempotencyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Entry> find(String userId, String key) {
        return jdbc.query(FIND,
                        new MapSqlParameterSource().addValue("userId", userId).addValue("key", key),
                        (rs, n) -> new Entry(rs.getString("request_hash"), rs.getObject("reservation_id", UUID.class)))
                .stream().findFirst();
    }

    public void insert(String userId, String key, String hash, UUID reservationId) {
        jdbc.update(INSERT, new MapSqlParameterSource()
                .addValue("userId", userId).addValue("key", key)
                .addValue("hash", hash).addValue("rid", reservationId));
    }
}
