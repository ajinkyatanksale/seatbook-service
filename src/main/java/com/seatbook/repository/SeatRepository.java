package com.seatbook.repository;

import com.seatbook.model.Seat;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public class SeatRepository {

    private static final String INSERT_SEAT =
            "INSERT INTO seats (show_id, label) VALUES (:showId, :label)";

    private static final String GET_SEATS_FOR_SHOW =
            "SELECT show_id, label, status, reservation_id FROM seats where show_id=:show_id ORDER BY label";

    private static final RowMapper<Seat> SEAT_MAPPER = (rs, rowNum) -> new Seat(
            rs.getObject("show_id", UUID.class),
            rs.getString("label"),
            rs.getString("status"),
            rs.getObject("reservation_id", UUID.class));

    private static final String CLAIM = """
        UPDATE seats SET status = 'confirmed', reservation_id = :rid
        WHERE show_id = :showId AND label IN (:labels) AND status = 'available'
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public SeatRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertAll(UUID showId, List<String> labels) {
        SqlParameterSource[] batch = labels.stream()
                .map(l -> new MapSqlParameterSource().addValue("showId", showId).addValue("label", l))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(INSERT_SEAT, batch);
    }

    public int claim(UUID showId, List<String> labels, UUID rid) {
        return jdbc.update(CLAIM, new MapSqlParameterSource()
                .addValue("showId", showId).addValue("labels", labels).addValue("rid", rid));
    }

    public List<Seat> findByShowId(UUID showId) {
        return jdbc.query(GET_SEATS_FOR_SHOW, new MapSqlParameterSource("show_id", showId), SEAT_MAPPER);
    }
}
