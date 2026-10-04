package io.github.xssirr17.reserve.reservation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record CreateReservationRequest(
    @NotNull UUID slotId,
    @NotBlank String userId,
    @NotNull @Positive @Max(10_000) Integer quantity
) {
    public static final int MAX_QUANTITY = 10_000;
}
