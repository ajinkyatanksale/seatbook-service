package com.seatbook.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

@Component
public class SeatGauges {

    private static final Logger log = LoggerFactory.getLogger(SeatGauges.class);
    private final JdbcTemplate jdbc;
    private final MultiGauge seatsAvailable;

    public SeatGauges(MeterRegistry registry, JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.seatsAvailable = MultiGauge.builder("seats.available")
                .description("Available seats per show (5 most recent shows), read from the database")
                .register(registry);
    }

    @Scheduled(fixedDelay = 1000)
    void refresh() {
        try {
            List<MultiGauge.Row<?>> rows = new ArrayList<>();
            jdbc.query("""
                    SELECT show_id, count(*) FILTER (WHERE status = 'available') AS avail
                    FROM seats
                    WHERE show_id IN (SELECT id FROM shows ORDER BY created_at DESC LIMIT 5)
                    GROUP BY show_id
                    """, (RowCallbackHandler) rs -> rows.add(
                    MultiGauge.Row.of(Tags.of("show_id", rs.getString("show_id")), rs.getLong("avail"))));
            seatsAvailable.register(rows, true);
        } catch (Exception e) {
            log.warn("seat gauge refresh failed: {}", e.toString());
        }
    }
}
