package com.seatbook.model;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record IdempotencyKey(@NotNull UUID userId,
                             @NotNull String key,
                             @NotNull String requestHash,
                             @NotNull UUID reservationId){
}
