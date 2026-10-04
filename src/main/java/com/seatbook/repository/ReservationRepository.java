package com.seatbook.repository;

import com.seatbook.model.Reservation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ReservationRepository {

    private static final String INSERT = """
            INSERT INTO reservations (id, show_id, user_id, seats, amount_paise, status)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND_BY_ID = """
            SELECT id, show_id, user_id, seats, amount_paise, status
            FROM reservations WHERE id = ?
            """;

    private static final String UPDATE_BY_ID_AND_STATUS = """
            UPDATE reservations set status = 'cancelled' WHERE id = ? and status = 'confirmed'
            """;

    private static final RowMapper<Reservation> MAPPER = (rs, n) -> {
        String[] seats = (String[]) rs.getArray("seats").getArray();
        return new Reservation(
                rs.getObject("id", UUID.class),
                rs.getObject("show_id", UUID.class),
                rs.getString("user_id"),
                List.of(seats),
                rs.getLong("amount_paise"),
                rs.getString("status"));
    };

    private final JdbcTemplate jdbc;

    public ReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Reservation r) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(INSERT);
            ps.setObject(1, r.id());
            ps.setObject(2, r.showId());
            ps.setString(3, r.userId());
            ps.setArray(4, con.createArrayOf("text", r.seats().toArray(new String[0])));
            ps.setLong(5, r.amountPaise());
            ps.setString(6, r.status());
            return ps;
        });
    }

    public Optional<Reservation> findById(UUID id) {
        return jdbc.query(FIND_BY_ID, MAPPER, id).stream().findFirst();
    }

    public int markCancelled(UUID id) {
        return jdbc.update(UPDATE_BY_ID_AND_STATUS, id);
    }
}
