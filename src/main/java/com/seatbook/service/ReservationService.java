package com.seatbook.service;

import com.seatbook.dto.responses.ReservationResponse;
import com.seatbook.error.DomainException;
import com.seatbook.error.ErrorCode;
import com.seatbook.model.Reservation;
import com.seatbook.model.Show;
import com.seatbook.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.seatbook.util.SeatLabels;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Service
public class ReservationService {

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final QuotaRepository quotaRepository;
    private final IdempotencyRepository idempotencyRepository;

    public ReservationService(ShowRepository showRepository, SeatRepository seatRepository,
                              ReservationRepository reservationRepository,
                              QuotaRepository quotaRepository,
                              IdempotencyRepository idempotencyRepository) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.reservationRepository = reservationRepository;
        this.quotaRepository = quotaRepository;
        this.idempotencyRepository = idempotencyRepository;
    }

    @Transactional
    public ReservationResponse reserve(String userId, UUID showId, List<String> rawSeats, String key) {
        List<String> seats = SeatLabels.getSortedLabels(rawSeats);

        // 1. serialize same-(user,key) requests; released at commit/rollback
        idempotencyRepository.lock(userId, key);

        // 2. replay or conflict
        String hash = requestHash(showId, seats);
        Optional<IdempotencyRepository.Entry> existing = idempotencyRepository.find(userId, key);
        if (existing.isPresent()) {
            if (!existing.get().requestHash().equals(hash)) {
                throw new DomainException(ErrorCode.IDEMPOTENCY_CONFLICT,
                        "Idempotency key was already used with a different request");
            }
            Reservation original = reservationRepository.findById(existing.get().reservationId())
                    .orElseThrow(() -> new IllegalStateException("Idempotency row without reservation"));
            return toResponse(original);
        }

        // 3. show (immutable, no lock needed)
        Show show = showRepository.findById(showId)
                .orElseThrow(() -> new DomainException(ErrorCode.SHOW_NOT_FOUND, "Show not found"));

        // 4. per-user limit: guarded increment, row lock held until commit
        quotaRepository.ensureRow(showId, userId);
        if (!quotaRepository.tryAdd(showId, userId, seats.size(), show.perUserLimit())) {
            throw new DomainException(ErrorCode.PER_USER_LIMIT,
                    "Limit of " + show.perUserLimit() + " seats per user exceeded");
        }

        // 5. lock seats in sorted order, then decide from the latest committed state
        Map<String, String> locked = seatRepository.lockSeats(showId, seats);
        if (locked.size() < seats.size()) {
            List<String> missing = seats.stream().filter(s -> !locked.containsKey(s)).toList();
            throw new DomainException(ErrorCode.UNKNOWN_SEAT, "Unknown seats: " + missing);
        }
        if (locked.values().stream().anyMatch(status -> !"available".equals(status))) {
            throw new DomainException(ErrorCode.SEAT_TAKEN, "One or more requested seats are already taken");
        }

        // 6. only now write the reservation (FK requires it before seats point at it)
        long amount = Math.multiplyExact(show.pricePaise(), (long) seats.size());
        UUID reservationId = UUID.randomUUID();
        reservationRepository.insert(new Reservation(reservationId, showId, userId, seats, amount, "confirmed"));

        // 7. guarded claim: still conditional on status='available' as a backstop
        int claimed = seatRepository.claim(showId, seats, reservationId);
        if (claimed != seats.size()) {
            // impossible while we hold the row locks; if it ever fires, the lock order is broken
            throw new IllegalStateException("Claimed " + claimed + " of " + seats.size() + " locked seats");
        }

        idempotencyRepository.insert(userId, key, hash, reservationId);
        return new ReservationResponse(reservationId, showId, userId, seats, amount, "confirmed");
    }

    private static ReservationResponse toResponse(Reservation r) {
        return new ReservationResponse(r.id(), r.showId(), r.userId(), r.seats(), r.amountPaise(), r.status());
    }

    private static String requestHash(UUID showId, List<String> sortedSeats) {
        StringBuilder sb = new StringBuilder(showId.toString());
        for (String s : sortedSeats) {
            sb.append('|').append(s.length()).append(':').append(s);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Transactional
    public ReservationResponse cancel(String userId, UUID reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESERVATION_NOT_FOUND, "Reservation not found"));

        if (!reservation.userId().equals(userId)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "You can only cancel your own reservations");
        }

        // same global order as reserve: quota row first, then seats in label order
        quotaRepository.lockRow(reservation.showId(), userId);

        if (reservationRepository.markCancelled(reservationId) == 0) {
            return toResponse(reservationRepository.findById(reservationId).orElseThrow());
        }

        seatRepository.lockOwnedSeats(reservation.showId(), reservationId);
        int freed = seatRepository.release(reservation.showId(), reservationId);
        quotaRepository.removeSeats(reservation.showId(), userId, freed);

        return new ReservationResponse(reservation.id(), reservation.showId(), userId,
                reservation.seats(), reservation.amountPaise(), "cancelled");
    }
}
