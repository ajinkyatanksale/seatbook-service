package com.seatbook.dto.responses;

public record CancelResult(ReservationResponse response, int seatsFreed) {}