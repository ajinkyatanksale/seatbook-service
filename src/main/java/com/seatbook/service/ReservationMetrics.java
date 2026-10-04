package com.seatbook.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ReservationMetrics {

    private final MeterRegistry registry;
    private final Counter confirmed, seatsConfirmed, cancelled, seatsReleased, retries;
    private final Map<String, Counter> declined = new ConcurrentHashMap<>();

    public ReservationMetrics(MeterRegistry registry) {
        this.registry = registry;
        confirmed     = Counter.builder("reservations.confirmed").description("New reservations committed").register(registry);
        seatsConfirmed = Counter.builder("seats.confirmed").description("Seats sold").register(registry);
        cancelled     = Counter.builder("reservations.cancelled").register(registry);
        seatsReleased = Counter.builder("seats.released").register(registry);
        retries       = Counter.builder("booking.retries").description("Deadlock/lock retries (should stay 0)").register(registry);

        for (String r : List.of("seat_taken", "per_user_limit", "idempotent_replay", "idempotency_conflict", "unknown_seat")) {
            declinedCounter(r);
        }
    }

    public void confirmed(int seats) { confirmed.increment(); seatsConfirmed.increment(seats); }
    public void cancelled(int seats) { cancelled.increment(); seatsReleased.increment(seats); }
    public void declined(String reason) { declinedCounter(reason).increment(); }
    public void replay() { declinedCounter("idempotent_replay").increment(); }
    public void retry() { retries.increment(); }

    private Counter declinedCounter(String reason) {
        return declined.computeIfAbsent(reason, r ->
                Counter.builder("reservations.declined").tag("reason", r).register(registry));
    }
}
