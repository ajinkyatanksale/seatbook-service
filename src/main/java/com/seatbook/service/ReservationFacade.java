package com.seatbook.service;

import com.seatbook.dto.responses.ReservationResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

@Service
public class ReservationFacade {

    private static final int MAX_ATTEMPTS = 10;

    private final ReservationService service;
    private final Semaphore permits;

    public ReservationFacade(ReservationService service,
                             @Value("${app.booking.max-concurrent:24}") int maxConcurrent) {
        this.service = service;
        this.permits = new Semaphore(maxConcurrent, true);   // fair: FIFO for the 20k waiters
    }

    public ReservationResponse reserve(String userId, UUID showId, List<String> seats, String key) {
        return guarded(() -> service.reserve(userId, showId, seats, key));
    }

    public ReservationResponse cancel(String userId, UUID reservationId) {
        return guarded(() -> service.cancel(userId, reservationId));
    }

    private <T> T guarded(Supplier<T> work) {
        permits.acquireUninterruptibly();          // virtual thread parks cheaply here
        try {
            for (int attempt = 1; ; attempt++) {
                try {
                    return work.get();
                } catch (PessimisticLockingFailureException e) {   // deadlock / lock timeout / serialization
                    if (attempt >= MAX_ATTEMPTS) throw e;
                    sleepQuietly(ThreadLocalRandom.current().nextLong(5, 25) * attempt);
                }
            }
        } finally {
            permits.release();
        }
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
