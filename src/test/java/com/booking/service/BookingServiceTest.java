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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private ShowRepository showRepository;

    @Mock
    private SeatRepository seatRepository;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private ReservationRepository reservationRepository;

    private Clock fixedClock;
    private BookingService bookingService;

    private final Instant baseTime = Instant.parse("2026-11-11T12:00:00Z");
    private final Duration holdDuration = Duration.ofMinutes(5);

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(baseTime, ZoneOffset.UTC);
        bookingService = new BookingServiceImpl(
                showRepository,
                seatRepository,
                bookingRepository,
                reservationRepository,
                fixedClock,
                holdDuration
        );
    }

    @Test
    @DisplayName("createHold successfully holds requested seats for 5 minutes")
    void testCreateHoldSuccess() {
        Long showId = 1L;
        String userId = "user-123";
        CreateHoldRequest request = new CreateHoldRequest(List.of(2L, 1L));

        Show show = new Show(showId, "Movie", baseTime.plusSeconds(3600), new BigDecimal("150.00"), baseTime);
        when(showRepository.findById(showId)).thenReturn(Optional.of(show));

        List<Seat> seats = List.of(new Seat(1L, showId, "A1"), new Seat(2L, showId, "A2"));
        when(seatRepository.findByShowIdAndIdIn(eq(showId), eq(List.of(1L, 2L)))).thenReturn(seats);

        when(reservationRepository.findActiveReservationsByShowIdAndSeatIds(eq(showId), eq(List.of(1L, 2L)), anySet()))
                .thenReturn(List.of());

        HoldResponse response = bookingService.createHold(showId, userId, request);

        assertThat(response).isNotNull();
        assertThat(response.bookingId()).isNotNull();
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.seats()).containsExactly(1L, 2L);
        assertThat(response.holdExpiresAt()).isEqualTo(baseTime.plus(Duration.ofMinutes(5)));
        assertThat(response.totalAmount()).isEqualByComparingTo(new BigDecimal("300.00"));

        ArgumentCaptor<Booking> bookingCaptor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(bookingCaptor.capture());
        Booking savedBooking = bookingCaptor.getValue();
        assertThat(savedBooking.getUserId()).isEqualTo(userId);
        assertThat(savedBooking.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(savedBooking.getTotalAmount()).isEqualByComparingTo(new BigDecimal("300.00"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Reservation>> resCaptor = ArgumentCaptor.forClass(List.class);
        verify(reservationRepository).saveAll(resCaptor.capture());
        List<Reservation> savedReservations = resCaptor.getValue();
        assertThat(savedReservations).hasSize(2);
        assertThat(savedReservations).allMatch(r -> r.getStatus() == ReservationStatus.HELD);
        assertThat(savedReservations).allMatch(r -> r.getHoldExpiresAt().equals(baseTime.plus(Duration.ofMinutes(5))));
    }

    @Test
    @DisplayName("createHold rejects empty or greater than 6 seats or duplicates")
    void testCreateHoldValidationErrors() {
        Long showId = 1L;
        String userId = "user-123";

        // Empty seats
        assertThatThrownBy(() -> bookingService.createHold(showId, userId, new CreateHoldRequest(List.of())))
                .isInstanceOf(InvalidBookingException.class);

        // > 6 seats
        assertThatThrownBy(() -> bookingService.createHold(showId, userId, new CreateHoldRequest(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L))))
                .isInstanceOf(InvalidBookingException.class)
                .hasMessageContaining("Cannot hold more than 6 seats");

        // Duplicate seats
        assertThatThrownBy(() -> bookingService.createHold(showId, userId, new CreateHoldRequest(List.of(1L, 2L, 1L))))
                .isInstanceOf(InvalidBookingException.class)
                .hasMessageContaining("Duplicate seat IDs");
    }

    @Test
    @DisplayName("createHold throws ResourceNotFoundException if show not found or seats invalid")
    void testCreateHoldNotFound() {
        Long showId = 1L;
        String userId = "user-123";

        when(showRepository.findById(showId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.createHold(showId, userId, new CreateHoldRequest(List.of(1L))))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Show not found");

        Show show = new Show(showId, "Movie", baseTime.plusSeconds(3600), new BigDecimal("100.00"), baseTime);
        when(showRepository.findById(showId)).thenReturn(Optional.of(show));
        when(seatRepository.findByShowIdAndIdIn(eq(showId), eq(List.of(1L, 2L))))
                .thenReturn(List.of(new Seat(1L, showId, "A1"))); // only 1 seat returned

        assertThatThrownBy(() -> bookingService.createHold(showId, userId, new CreateHoldRequest(List.of(1L, 2L))))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("One or more seat IDs are invalid");
    }

    @Test
    @DisplayName("createHold enforces all-or-nothing: fails if any seat is already held or booked")
    void testCreateHoldAllOrNothingConflict() {
        Long showId = 1L;
        String userId = "user-123";
        CreateHoldRequest request = new CreateHoldRequest(List.of(1L, 2L));

        Show show = new Show(showId, "Movie", baseTime.plusSeconds(3600), new BigDecimal("100.00"), baseTime);
        when(showRepository.findById(showId)).thenReturn(Optional.of(show));

        List<Seat> seats = List.of(new Seat(1L, showId, "A1"), new Seat(2L, showId, "A2"));
        when(seatRepository.findByShowIdAndIdIn(eq(showId), eq(List.of(1L, 2L)))).thenReturn(seats);

        // Seat 2 is currently held by someone else (not yet expired)
        Reservation activeHoldOnSeat2 = new Reservation(
                20L,
                UUID.randomUUID(),
                showId,
                2L,
                ReservationStatus.HELD,
                baseTime.plusSeconds(180)
        );
        when(reservationRepository.findActiveReservationsByShowIdAndSeatIds(eq(showId), eq(List.of(1L, 2L)), anySet()))
                .thenReturn(List.of(activeHoldOnSeat2));

        assertThatThrownBy(() -> bookingService.createHold(showId, userId, request))
                .isInstanceOf(SeatUnavailableException.class)
                .hasMessageContaining("Seats unavailable: [2]");

        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @Test
    @DisplayName("createHold allows re-holding when existing hold is expired (lazy release)")
    void testCreateHoldAllowsReholdingExpiredReservation() {
        Long showId = 1L;
        String userId = "user-123";
        CreateHoldRequest request = new CreateHoldRequest(List.of(1L));

        Show show = new Show(showId, "Movie", baseTime.plusSeconds(3600), new BigDecimal("100.00"), baseTime);
        when(showRepository.findById(showId)).thenReturn(Optional.of(show));

        List<Seat> seats = List.of(new Seat(1L, showId, "A1"));
        when(seatRepository.findByShowIdAndIdIn(eq(showId), eq(List.of(1L)))).thenReturn(seats);

        // Seat 1 was held, but expired 10 seconds ago
        Reservation expiredHold = new Reservation(
                30L,
                UUID.randomUUID(),
                showId,
                1L,
                ReservationStatus.HELD,
                baseTime.minusSeconds(10)
        );
        when(reservationRepository.findActiveReservationsByShowIdAndSeatIds(eq(showId), eq(List.of(1L)), anySet()))
                .thenReturn(List.of(expiredHold));

        HoldResponse response = bookingService.createHold(showId, userId, request);

        assertThat(response).isNotNull();
        assertThat(response.seats()).containsExactly(1L);
        assertThat(expiredHold.getStatus()).isEqualTo(ReservationStatus.RELEASED);

        verify(bookingRepository).save(any(Booking.class));
        verify(reservationRepository).saveAllAndFlush(anyList());
        verify(reservationRepository).saveAll(anyList());
    }
}
