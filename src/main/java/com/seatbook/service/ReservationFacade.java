package com.seatbook.service;

import com.seatbook.dto.responses.CancelResult;
import com.seatbook.dto.responses.ReserveResult;
import com.seatbook.error.DomainException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Service
public class ReservationFacade {

    private static final int MAX_ATTEMPTS = 10;

    private final ReservationService service;
    private final Semaphore permits;
    private final ReservationMetrics metrics;

    private final AtomicLong retries = new AtomicLong();
    public long retryCount() { return retries.get(); }

    public ReservationFacade(ReservationService service,
                             @Value("${app.booking.max-concurrent:24}") int maxConcurrent,
                             ReservationMetrics metrics,
                             MeterRegistry registry) {
        this.service = service;
        this.permits = new Semaphore(maxConcurrent, true);
        this.metrics = metrics;
        Gauge.builder("booking.permits.available", permits, Semaphore::availablePermits).register(registry);
        Gauge.builder("booking.queue.length", permits, Semaphore::getQueueLength).register(registry);
    }

    public ReserveResult reserve(String userId, UUID showId, List<String> seats, String key) {
        try {
            ReserveResult r = guarded(() -> service.reserve(userId, showId, seats, key));
            if (r.replayed()) metrics.replay();
            else metrics.confirmed(r.response().seats().size());
            return r;
        } catch (DomainException e) {
            metrics.declined(e.getErrorCode().getValue());
            throw e;
        }
    }

    public CancelResult cancel(String userId, UUID reservationId) {
        CancelResult r = guarded(() -> service.cancel(userId, reservationId));
        if (r.seatsFreed() > 0) metrics.cancelled(r.seatsFreed());   // 0 means it was already cancelled
        return r;
    }

    private <T> T guarded(Supplier<T> work) {
        permits.acquireUninterruptibly();          // virtual thread parks cheaply here
        try {
            for (int attempt = 1; ; attempt++) {
                try {
                    return work.get();
                } catch (PessimisticLockingFailureException e) {
                    retries.incrementAndGet();
                    metrics.retry();
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
