package com.booking.controller;

import com.booking.dto.CreateHoldRequest;
import com.booking.dto.CreateShowRequest;
import com.booking.dto.HoldResponse;
import com.booking.dto.SeatResponse;
import com.booking.dto.ShowResponse;
import com.booking.service.BookingService;
import com.booking.service.ShowService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/shows")
public class ShowController {

    private final ShowService showService;
    private final BookingService bookingService;

    public ShowController(ShowService showService, BookingService bookingService) {
        this.showService = showService;
        this.bookingService = bookingService;
    }

    @PostMapping
    public ResponseEntity<ShowResponse> createShow(@Valid @RequestBody CreateShowRequest request) {
        ShowResponse response = showService.createShow(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{showId}/seats")
    public ResponseEntity<List<SeatResponse>> getSeatMap(@PathVariable("showId") Long showId) {
        List<SeatResponse> seats = showService.getSeatMap(showId);
        return ResponseEntity.ok(seats);
    }

    @PostMapping("/{showId}/holds")
    public ResponseEntity<HoldResponse> createHold(
            @PathVariable("showId") Long showId,
            @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId,
            @Valid @RequestBody CreateHoldRequest request) {
        HoldResponse response = bookingService.createHold(showId, userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
