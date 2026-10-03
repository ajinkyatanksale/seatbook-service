package com.seatbook.model;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record UserShowQuota(@NotNull UUID showId,
                            @NotNull UUID userId,
                            @NotNull Integer seatCount) {
}
