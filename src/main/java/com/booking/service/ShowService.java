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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ShowService {

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final Clock clock;

    public ShowService(ShowRepository showRepository,
                       SeatRepository seatRepository,
                       ReservationRepository reservationRepository,
                       Clock clock) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.reservationRepository = reservationRepository;
        this.clock = clock;
    }

    @Transactional
    public ShowResponse createShow(CreateShowRequest request) {
        Instant now = Instant.now(clock);
        Show show = new Show(
                null,
                request.title(),
                request.startsAt(),
                request.pricePerSeat(),
                now
        );
        Show savedShow = showRepository.save(show);

        List<Seat> seatsToSave = new ArrayList<>();
        for (int row = 0; row < request.rows(); row++) {
            char rowLetter = (char) ('A' + row);
            for (int col = 1; col <= request.seatsPerRow(); col++) {
                String label = rowLetter + String.valueOf(col);
                seatsToSave.add(new Seat(savedShow.getId(), label));
            }
        }
        List<Seat> savedSeats = seatRepository.saveAll(seatsToSave);

        List<SeatResponse> seatResponses = savedSeats.stream()
                .map(seat -> new SeatResponse(seat.getId(), seat.getLabel(), "AVAILABLE"))
                .toList();

        return new ShowResponse(
                savedShow.getId(),
                savedShow.getTitle(),
                savedShow.getStartsAt(),
                savedShow.getPricePerSeat(),
                savedShow.getCreatedAt(),
                seatResponses
        );
    }

    @Transactional(readOnly = true)
    public List<SeatResponse> getSeatMap(Long showId) {
        if (!showRepository.existsById(showId)) {
            throw new ResourceNotFoundException("Show not found with id: " + showId);
        }

        List<Seat> seats = seatRepository.findByShowIdOrderByLabelAsc(showId);
        List<Reservation> activeReservations = reservationRepository.findActiveReservationsByShowId(
                showId,
                Set.of(ReservationStatus.HELD, ReservationStatus.CONFIRMED)
        );

        Instant now = Instant.now(clock);
        Map<Long, Reservation> reservationBySeatId = activeReservations.stream()
                .collect(Collectors.toMap(
                        Reservation::getSeatId,
                        r -> r,
                        (r1, r2) -> r1.getId() > r2.getId() ? r1 : r2
                ));

        return seats.stream()
                .map(seat -> {
                    Reservation res = reservationBySeatId.get(seat.getId());
                    String status = "AVAILABLE";
                    if (res != null) {
                        if (res.getStatus() == ReservationStatus.CONFIRMED) {
                            status = "BOOKED";
                        } else if (res.getStatus() == ReservationStatus.HELD) {
                            // If hold expired, seat is dynamically treated as AVAILABLE
                            if (res.getHoldExpiresAt() != null && res.getHoldExpiresAt().isAfter(now)) {
                                status = "HELD";
                            } else {
                                status = "AVAILABLE";
                            }
                        }
                    }
                    return new SeatResponse(seat.getId(), seat.getLabel(), status);
                })
                .toList();
    }
}
