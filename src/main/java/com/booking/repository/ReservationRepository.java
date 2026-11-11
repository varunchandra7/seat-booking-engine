package com.booking.repository;

import com.booking.entity.Reservation;
import com.booking.entity.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    @Query("SELECT r FROM Reservation r WHERE r.showId = :showId AND r.status IN :statuses")
    List<Reservation> findActiveReservationsByShowId(
            @Param("showId") Long showId,
            @Param("statuses") Collection<ReservationStatus> statuses
    );

    @Query("SELECT r FROM Reservation r WHERE r.showId = :showId AND r.seatId IN :seatIds AND r.status IN :statuses")
    List<Reservation> findActiveReservationsByShowIdAndSeatIds(
            @Param("showId") Long showId,
            @Param("seatIds") Collection<Long> seatIds,
            @Param("statuses") Collection<ReservationStatus> statuses
    );
}
