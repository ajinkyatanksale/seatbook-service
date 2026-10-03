package com.seatbook.model;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record Reservations(@NotNull UUID id,
                           @NotNull UUID showId,
                           @NotNull UUID userId,
                           @NotNull List<String> seats,
                           @NotNull Long amountPaise,
                           @NotNull String status) {
}
