package com.seatbook.model;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record Seat(@NotNull UUID showId,
                   @NotNull String label,
                   @NotNull String status,
                   @NotNull UUID reservationId) {
}
