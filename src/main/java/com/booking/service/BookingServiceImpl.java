package com.booking.service;

import com.booking.dto.CreateHoldRequest;
import com.booking.dto.HoldResponse;
import com.booking.entity.Booking;
import com.booking.entity.BookingStatus;
import com.booking.entity.Reservation;
import com.booking.entity.ReservationStatus;
import com.booking.entity.Seat;
import com.booking.entity.Show;
import com.booking.exception.InvalidBookingException;
import com.booking.exception.ResourceNotFoundException;
import com.booking.exception.SeatUnavailableException;
import com.booking.repository.BookingRepository;
import com.booking.repository.ReservationRepository;
import com.booking.repository.SeatRepository;
import com.booking.repository.ShowRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BookingServiceImpl implements BookingService {

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final BookingRepository bookingRepository;
    private final ReservationRepository reservationRepository;
    private final Clock clock;
    private final Duration holdDuration;

    public BookingServiceImpl(ShowRepository showRepository,
                              SeatRepository seatRepository,
                              BookingRepository bookingRepository,
                              ReservationRepository reservationRepository,
                              Clock clock,
                              @Value("${app.hold-duration:PT5M}") Duration holdDuration) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.bookingRepository = bookingRepository;
        this.reservationRepository = reservationRepository;
        this.clock = clock;
        this.holdDuration = holdDuration;
    }

    @Override
    @Transactional
    public HoldResponse createHold(Long showId, String userId, CreateHoldRequest request) {
        if (request.seatIds() == null || request.seatIds().isEmpty()) {
            throw new InvalidBookingException("Seat IDs must not be empty");
        }
        if (request.seatIds().size() > 6) {
            throw new InvalidBookingException("Cannot hold more than 6 seats in a single booking");
        }
        Set<Long> uniqueSeatIds = new HashSet<>(request.seatIds());
        if (uniqueSeatIds.size() != request.seatIds().size()) {
            throw new InvalidBookingException("Duplicate seat IDs requested");
        }

        Show show = showRepository.findById(showId)
                .orElseThrow(() -> new ResourceNotFoundException("Show not found with id: " + showId));

        // Sort seat IDs ascending (consistent lock order / deterministic processing)
        List<Long> sortedSeatIds = request.seatIds().stream().sorted().toList();

        List<Seat> seats = seatRepository.findByShowIdAndIdIn(showId, sortedSeatIds);
        if (seats.size() != sortedSeatIds.size()) {
            throw new ResourceNotFoundException("One or more seat IDs are invalid for show: " + showId);
        }

        Instant now = Instant.now(clock);
        Instant holdExpiresAt = now.plus(holdDuration);

        // Fetch active reservations for requested seats
        List<Reservation> existingActive = reservationRepository.findActiveReservationsByShowIdAndSeatIds(
                showId,
                sortedSeatIds,
                Set.of(ReservationStatus.HELD, ReservationStatus.CONFIRMED)
        );

        Map<Long, List<Reservation>> activeBySeatId = existingActive.stream()
                .collect(Collectors.groupingBy(Reservation::getSeatId));

        // All-or-nothing check: ensure no seat is CONFIRMED or currently active HELD
        List<Long> unavailableSeatIds = new ArrayList<>();
        List<Reservation> staleReservationsToRelease = new ArrayList<>();

        for (Long seatId : sortedSeatIds) {
            List<Reservation> seatReservations = activeBySeatId.getOrDefault(seatId, List.of());
            for (Reservation res : seatReservations) {
                if (res.getStatus() == ReservationStatus.CONFIRMED) {
                    unavailableSeatIds.add(seatId);
                } else if (res.getStatus() == ReservationStatus.HELD) {
                    if (res.getHoldExpiresAt() != null && res.getHoldExpiresAt().isAfter(now)) {
                        unavailableSeatIds.add(seatId);
                    } else {
                        // Stale hold expired: can be lazily marked RELEASED
                        staleReservationsToRelease.add(res);
                    }
                }
            }
        }

        if (!unavailableSeatIds.isEmpty()) {
            throw new SeatUnavailableException("Seats unavailable: " + unavailableSeatIds);
        }

        // Release stale reservations and flush to ensure DB updates execute before new inserts
        for (Reservation stale : staleReservationsToRelease) {
            stale.setStatus(ReservationStatus.RELEASED);
        }
        if (!staleReservationsToRelease.isEmpty()) {
            reservationRepository.saveAllAndFlush(staleReservationsToRelease);
        }

        // Calculate total amount
        BigDecimal totalAmount = show.getPricePerSeat().multiply(BigDecimal.valueOf(sortedSeatIds.size()));

        // Create booking
        UUID bookingId = UUID.randomUUID();
        Booking booking = new Booking(
                bookingId,
                showId,
                userId,
                BookingStatus.PENDING,
                holdExpiresAt,
                totalAmount,
                now,
                now
        );
        bookingRepository.save(booking);

        // Create reservations
        List<Reservation> newReservations = sortedSeatIds.stream()
                .map(seatId -> new Reservation(bookingId, showId, seatId, ReservationStatus.HELD, holdExpiresAt))
                .toList();
        reservationRepository.saveAll(newReservations);

        return new HoldResponse(
                bookingId,
                booking.getStatus().name(),
                sortedSeatIds,
                holdExpiresAt,
                totalAmount
        );
    }
}
