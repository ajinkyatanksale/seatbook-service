package com.seatbook.dto.requests;

import jakarta.validation.constraints.*;

import java.util.List;

public record CreateShowRequest(@NotBlank @Size(max = 200) String name,
                                @NotEmpty @Size(max = 50000) List<@NotBlank @Size(max = 20) String> seats,
                                @NotNull @Min(0) @Max(10_000_000_000L) Long pricePaise,
                                @Min(1) @Max(1000) Integer perUserLimit) {
}
