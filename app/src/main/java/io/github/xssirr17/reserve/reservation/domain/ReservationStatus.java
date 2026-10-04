package io.github.xssirr17.reserve.reservation.domain;

import java.util.Map;
import java.util.Set;

public enum ReservationStatus {
    PENDING,
    CONFIRMED,
    CANCELLED,
    EXPIRED,
    COMPLETED;

    private static final Map<ReservationStatus, Set<ReservationStatus>> ALLOWED_TRANSITIONS = Map.of(
        PENDING, Set.of(CONFIRMED, CANCELLED, EXPIRED),
        CONFIRMED, Set.of(COMPLETED, CANCELLED),
        CANCELLED, Set.of(),
        EXPIRED, Set.of(),
        COMPLETED, Set.of()
    );

    public boolean canTransitionTo(ReservationStatus next) {
        if (next == null) {
            return false;
        }
        return allowedTransitions().contains(next);
    }

    public Set<ReservationStatus> allowedTransitions() {
        return ALLOWED_TRANSITIONS.getOrDefault(this, Set.of());
    }

    public boolean isTerminal() {
        return allowedTransitions().isEmpty();
    }
}
