package io.github.xssirr17.reserve.common.error;

import java.util.UUID;

public class InsufficientCapacityException extends ConflictException {

    private final UUID slotId;
    private final Integer requested;
    private final Integer available;

    public InsufficientCapacityException(String message) {
        super(message);
        this.slotId = null;
        this.requested = null;
        this.available = null;
    }

    public InsufficientCapacityException(UUID slotId, int requested, int available) {
        super(String.format("Insufficient capacity for slot %s: requested %d, available %d", slotId, requested, available));
        this.slotId = slotId;
        this.requested = requested;
        this.available = available;
    }

    public UUID getSlotId() {
        return slotId;
    }

    public Integer getRequested() {
        return requested;
    }

    public Integer getAvailable() {
        return available;
    }
}
