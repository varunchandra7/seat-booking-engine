package com.booking.dto;

public record SeatResponse(
        Long seatId,
        String label,
        String status
) {
}
