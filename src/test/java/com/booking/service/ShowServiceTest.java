package com.booking.service;

import com.booking.dto.CreateShowRequest;
import com.booking.dto.SeatResponse;
import com.booking.dto.ShowResponse;
import com.booking.entity.Reservation;
import com.booking.entity.ReservationStatus;
import com.booking.entity.Seat;
import com.booking.entity.Show;
import com.booking.exception.ResourceNotFoundException;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShowServiceTest {

    @Mock
    private ShowRepository showRepository;

    @Mock
    private SeatRepository seatRepository;

    @Mock
    private ReservationRepository reservationRepository;

    private Clock fixedClock;
    private ShowService showService;

    private final Instant baseTime = Instant.parse("2026-11-11T12:00:00Z");

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(baseTime, ZoneOffset.UTC);
        showService = new ShowService(showRepository, seatRepository, reservationRepository, fixedClock);
    }

    @Test
    @DisplayName("createShow generates requested rows and seats per row correctly (e.g. A1..B3)")
    void testCreateShowGeneratesGridSeats() {
        CreateShowRequest request = new CreateShowRequest(
                "Interstellar",
                baseTime.plusSeconds(3600),
                new BigDecimal("250.00"),
                2,
                3
        );

        Show savedShow = new Show(1L, request.title(), request.startsAt(), request.pricePerSeat(), baseTime);
        when(showRepository.save(any(Show.class))).thenReturn(savedShow);

        List<Seat> mockSavedSeats = List.of(
                new Seat(101L, 1L, "A1"),
                new Seat(102L, 1L, "A2"),
                new Seat(103L, 1L, "A3"),
                new Seat(104L, 1L, "B1"),
                new Seat(105L, 1L, "B2"),
                new Seat(106L, 1L, "B3")
        );
        when(seatRepository.saveAll(anyList())).thenReturn(mockSavedSeats);

        ShowResponse response = showService.createShow(request);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.title()).isEqualTo("Interstellar");
        assertThat(response.seats()).hasSize(6);
        assertThat(response.seats().stream().map(SeatResponse::label))
                .containsExactly("A1", "A2", "A3", "B1", "B2", "B3");
        assertThat(response.seats()).allMatch(s -> s.status().equals("AVAILABLE"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Seat>> seatListCaptor = ArgumentCaptor.forClass(List.class);
        verify(seatRepository).saveAll(seatListCaptor.capture());
        List<Seat> generated = seatListCaptor.getValue();
        assertThat(generated).hasSize(6);
        assertThat(generated.get(0).getLabel()).isEqualTo("A1");
        assertThat(generated.get(5).getLabel()).isEqualTo("B3");
    }

    @Test
    @DisplayName("getSeatMap throws ResourceNotFoundException when show does not exist")
    void testGetSeatMapShowNotFound() {
        when(showRepository.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> showService.getSeatMap(999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Show not found");
    }

    @Test
    @DisplayName("getSeatMap dynamically reflects expired HELD reservations as AVAILABLE")
    void testGetSeatMapDynamicStatusMapping() {
        Long showId = 1L;
        when(showRepository.existsById(showId)).thenReturn(true);

        List<Seat> seats = List.of(
                new Seat(1L, showId, "A1"),
                new Seat(2L, showId, "A2"),
                new Seat(3L, showId, "A3"),
                new Seat(4L, showId, "A4")
        );
        when(seatRepository.findByShowIdOrderByLabelAsc(showId)).thenReturn(seats);

        // Seat 1: CONFIRMED (BOOKED)
        Reservation r1 = new Reservation(10L, UUID.randomUUID(), showId, 1L, ReservationStatus.CONFIRMED, null);
        // Seat 2: Active HELD (expires in +2 mins)
        Reservation r2 = new Reservation(11L, UUID.randomUUID(), showId, 2L, ReservationStatus.HELD, baseTime.plusSeconds(120));
        // Seat 3: Expired HELD (expired 1 min ago) -> should be AVAILABLE
        Reservation r3 = new Reservation(12L, UUID.randomUUID(), showId, 3L, ReservationStatus.HELD, baseTime.minusSeconds(60));
        // Seat 4: No reservation -> AVAILABLE

        when(reservationRepository.findActiveReservationsByShowId(eq(showId), anySet()))
                .thenReturn(List.of(r1, r2, r3));

        List<SeatResponse> seatResponses = showService.getSeatMap(showId);

        assertThat(seatResponses).hasSize(4);
        assertThat(seatResponses.get(0)).isEqualTo(new SeatResponse(1L, "A1", "BOOKED"));
        assertThat(seatResponses.get(1)).isEqualTo(new SeatResponse(2L, "A2", "HELD"));
        assertThat(seatResponses.get(2)).isEqualTo(new SeatResponse(3L, "A3", "AVAILABLE"));
        assertThat(seatResponses.get(3)).isEqualTo(new SeatResponse(4L, "A4", "AVAILABLE"));
    }
}
