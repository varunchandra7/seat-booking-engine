package com.booking.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public record CreateShowRequest(
        @NotBlank(message = "Title must not be blank")
        @Size(max = 200, message = "Title must not exceed 200 characters")
        String title,

        @NotNull(message = "startsAt must not be null")
        Instant startsAt,

        @NotNull(message = "pricePerSeat must not be null")
        @DecimalMin(value = "0.01", message = "pricePerSeat must be greater than zero")
        BigDecimal pricePerSeat,

        @NotNull(message = "rows must not be null")
        @Min(value = 1, message = "rows must be at least 1")
        @Max(value = 26, message = "rows must not exceed 26")
        Integer rows,

        @NotNull(message = "seatsPerRow must not be null")
        @Min(value = 1, message = "seatsPerRow must be at least 1")
        @Max(value = 50, message = "seatsPerRow must not exceed 50")
        Integer seatsPerRow
) {
}
