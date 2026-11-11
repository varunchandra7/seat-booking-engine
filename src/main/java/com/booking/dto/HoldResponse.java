package com.booking.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record HoldResponse(
        UUID bookingId,
        String status,
        List<Long> seats,
        Instant holdExpiresAt,
        BigDecimal totalAmount
) {
}
