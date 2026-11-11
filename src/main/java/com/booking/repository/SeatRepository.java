package com.booking.repository;

import com.booking.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SeatRepository extends JpaRepository<Seat, Long> {
    List<Seat> findByShowIdOrderByLabelAsc(Long showId);
    List<Seat> findByShowIdAndIdIn(Long showId, List<Long> seatIds);
}
