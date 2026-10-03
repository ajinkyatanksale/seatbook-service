package com.seatbook.model;

import java.util.UUID;

public record Show(UUID id,
                   String name,
                   Long pricePaise,
                   Integer perUserLimit) {
}
