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

        Show show = showRepository.findById(showId)
                .orElseThrow(() -> new DomainException(ErrorCode.SHOW_NOT_FOUND, "Show not found"));

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

        int current = quotaRepository.getCount(showId, userId);
        if (current + seats.size() > show.perUserLimit()) {
            throw new DomainException(ErrorCode.PER_USER_LIMIT,
                    "Limit of " + show.perUserLimit() + " seats per user exceeded");
        }

        long amount = Math.multiplyExact(show.pricePaise(), (long) seats.size());
        UUID reservationId = UUID.randomUUID();
        reservationRepository.insert(new Reservation(reservationId, showId, userId, seats, amount, "confirmed"));

        int claimed = seatRepository.claim(showId, seats, reservationId);
        if (claimed != seats.size()) {
            Set<String> found = seatRepository.findExistingLabels(showId, seats);
            if (found.size() < seats.size()) {
                List<String> missing = seats.stream().filter(s -> !found.contains(s)).toList();
                throw new DomainException(ErrorCode.UNKNOWN_SEAT, "Unknown seats: " + missing);
            }
            throw new DomainException(ErrorCode.SEAT_TAKEN, "One or more requested seats are already taken");
        }

        quotaRepository.addSeats(showId, userId, seats.size());
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
}
