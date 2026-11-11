package com.booking.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ShowResponse(
        Long id,
        String title,
        Instant startsAt,
        BigDecimal pricePerSeat,
        Instant createdAt,
        List<SeatResponse> seats
) {
}
