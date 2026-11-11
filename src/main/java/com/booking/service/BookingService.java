package com.booking.service;

import com.booking.dto.CreateHoldRequest;
import com.booking.dto.HoldResponse;

public interface BookingService {
    HoldResponse createHold(Long showId, String userId, CreateHoldRequest request);
}
