package io.github.xssirr17.reserve.slot.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

public record CreateSlotRequest(
    @NotNull Instant startTime,
    @NotNull Instant endTime,
    @NotNull @Positive @Max(1_000_000) Integer capacity
) {
    public static final int MAX_CAPACITY = 1_000_000;
    @AssertTrue(message = "endTime must be after startTime")
    public boolean isTimeRangeValid() {
        if (startTime == null || endTime == null) {
            return true;
        }
        return endTime.isAfter(startTime);
    }
}
