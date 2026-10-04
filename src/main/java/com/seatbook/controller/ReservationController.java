package com.seatbook.controller;

import com.seatbook.auth.AuthenticationUser;
import com.seatbook.dto.requests.ReserveRequest;
import com.seatbook.dto.responses.ReservationResponse;
import com.seatbook.error.DomainException;
import com.seatbook.error.ErrorCode;
import com.seatbook.service.ReservationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class ReservationController {

    private static final int MAX_KEY_LENGTH = 128;

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping("/shows/{id}/reserve")
    public ResponseEntity<ReservationResponse> reserve(
            @RequestAttribute("authUser") AuthenticationUser user,
            @PathVariable("id") UUID showId,
            @RequestHeader(value = "Idempotency-Key", required = false) String headerKey,
            @Valid @RequestBody ReserveRequest request) {

        String key = resolveKey(headerKey, request.idempotencyKey());
        ReservationResponse response = reservationService.reserve(user.userId(), showId, request.seats(), key);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    private static String resolveKey(String header, String body) {
        String h = blankToNull(header);
        String b = blankToNull(body);
        if (h != null && b != null && !h.equals(b)) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "Idempotency-Key header and idempotency_key body field differ");
        }
        String key = h != null ? h : b;
        if (key == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "idempotency key is required");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "idempotency key too long");
        }
        return key;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
