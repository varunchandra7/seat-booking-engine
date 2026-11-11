package com.booking.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateHoldRequest(
        @NotEmpty(message = "seatIds must not be empty")
        @Size(min = 1, max = 6, message = "seatIds count must be between 1 and 6")
        List<Long> seatIds
) {
}
