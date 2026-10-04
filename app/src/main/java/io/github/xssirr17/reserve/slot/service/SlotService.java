package io.github.xssirr17.reserve.slot.service;

import io.github.xssirr17.reserve.slot.dto.CreateSlotRequest;
import io.github.xssirr17.reserve.slot.dto.SlotResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class SlotService {

    public SlotResponse createSlot(UUID resourceId, CreateSlotRequest request) {
        throw new UnsupportedOperationException("TODO");
    }

    public Page<SlotResponse> getAvailability(UUID resourceId, Instant from, Instant to, Pageable pageable) {
        throw new UnsupportedOperationException("TODO");
    }
}
