package io.github.xssirr17.reserve.reservation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record CreateReservationRequest(
    @NotNull UUID slotId,
    @NotBlank String userId,
    @NotNull @Positive Integer quantity
) {
}
