package io.github.xssirr17.reserve.slot.dto;

import io.github.xssirr17.reserve.slot.domain.Slot;

import java.time.Instant;
import java.util.UUID;

public record SlotResponse(
    UUID id,
    UUID resourceId,
    Instant startTime,
    Instant endTime,
    int capacity,
    int reserved,
    Instant createdAt
) {
    public static SlotResponse from(Slot slot) {
        return new SlotResponse(
            slot.getId(),
            slot.getResourceId(),
            slot.getStartTime(),
            slot.getEndTime(),
            slot.getCapacity(),
            slot.getReserved(),
            slot.getCreatedAt()
        );
    }
}
