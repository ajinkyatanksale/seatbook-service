package com.seatbook.repository;

import com.seatbook.model.Show;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class ShowRepository {
    private static final String INSERT = """
            INSERT INTO shows (id, name, price_paise, per_user_limit)
            VALUES (:id, :name, :pricePaise, :perUserLimit)
            """;

    private static final String FIND_BY_ID = """
            SELECT id, name, price_paise, per_user_limit
            FROM shows WHERE id = :id
            """;

    private static final RowMapper<Show> SHOW_MAPPER = (rs, rowNum) -> new Show(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getLong("price_paise"),
            rs.getInt("per_user_limit"));

    private final NamedParameterJdbcTemplate jdbc;

    public ShowRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Show show) {
        jdbc.update(INSERT, new MapSqlParameterSource()
                .addValue("id", show.id())
                .addValue("name", show.name())
                .addValue("pricePaise", show.pricePaise())
                .addValue("perUserLimit", show.perUserLimit()));
    }

    public Optional<Show> findById(UUID id) {
        return jdbc.query(FIND_BY_ID, new MapSqlParameterSource("id", id), SHOW_MAPPER)
                .stream().findFirst();
    }
}
