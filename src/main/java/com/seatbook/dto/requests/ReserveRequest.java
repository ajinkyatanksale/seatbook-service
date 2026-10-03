package com.seatbook.dto.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ReserveRequest(@NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 20) String> seats,
                             @Size(max = 128) String idempotencyKey) {
}
