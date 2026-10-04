package io.github.xssirr17.reserve.reservation.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReservationStatusTest {

    @Test
    @DisplayName("Verify all 25 state transition pairs explicitly")
    void testAllStateTransitions() {
        assertAll("Allowed transitions",
            () -> assertTrue(ReservationStatus.PENDING.canTransitionTo(ReservationStatus.CONFIRMED)),
            () -> assertTrue(ReservationStatus.PENDING.canTransitionTo(ReservationStatus.CANCELLED)),
            () -> assertTrue(ReservationStatus.PENDING.canTransitionTo(ReservationStatus.EXPIRED)),
            () -> assertTrue(ReservationStatus.CONFIRMED.canTransitionTo(ReservationStatus.COMPLETED)),
            () -> assertTrue(ReservationStatus.CONFIRMED.canTransitionTo(ReservationStatus.CANCELLED))
        );

        assertAll("Disallowed transitions from PENDING",
            () -> assertFalse(ReservationStatus.PENDING.canTransitionTo(ReservationStatus.PENDING)),
            () -> assertFalse(ReservationStatus.PENDING.canTransitionTo(ReservationStatus.COMPLETED))
        );

        assertAll("Disallowed transitions from CONFIRMED",
            () -> assertFalse(ReservationStatus.CONFIRMED.canTransitionTo(ReservationStatus.PENDING)),
            () -> assertFalse(ReservationStatus.CONFIRMED.canTransitionTo(ReservationStatus.CONFIRMED)),
            () -> assertFalse(ReservationStatus.CONFIRMED.canTransitionTo(ReservationStatus.EXPIRED))
        );

        assertAll("Disallowed transitions from CANCELLED (terminal)",
            () -> assertFalse(ReservationStatus.CANCELLED.canTransitionTo(ReservationStatus.PENDING)),
            () -> assertFalse(ReservationStatus.CANCELLED.canTransitionTo(ReservationStatus.CONFIRMED)),
            () -> assertFalse(ReservationStatus.CANCELLED.canTransitionTo(ReservationStatus.CANCELLED)),
            () -> assertFalse(ReservationStatus.CANCELLED.canTransitionTo(ReservationStatus.EXPIRED)),
            () -> assertFalse(ReservationStatus.CANCELLED.canTransitionTo(ReservationStatus.COMPLETED))
        );

        assertAll("Disallowed transitions from EXPIRED (terminal)",
            () -> assertFalse(ReservationStatus.EXPIRED.canTransitionTo(ReservationStatus.PENDING)),
            () -> assertFalse(ReservationStatus.EXPIRED.canTransitionTo(ReservationStatus.CONFIRMED)),
            () -> assertFalse(ReservationStatus.EXPIRED.canTransitionTo(ReservationStatus.CANCELLED)),
            () -> assertFalse(ReservationStatus.EXPIRED.canTransitionTo(ReservationStatus.EXPIRED)),
            () -> assertFalse(ReservationStatus.EXPIRED.canTransitionTo(ReservationStatus.COMPLETED))
        );

        assertAll("Disallowed transitions from COMPLETED (terminal)",
            () -> assertFalse(ReservationStatus.COMPLETED.canTransitionTo(ReservationStatus.PENDING)),
            () -> assertFalse(ReservationStatus.COMPLETED.canTransitionTo(ReservationStatus.CONFIRMED)),
            () -> assertFalse(ReservationStatus.COMPLETED.canTransitionTo(ReservationStatus.CANCELLED)),
            () -> assertFalse(ReservationStatus.COMPLETED.canTransitionTo(ReservationStatus.EXPIRED)),
            () -> assertFalse(ReservationStatus.COMPLETED.canTransitionTo(ReservationStatus.COMPLETED))
        );
    }

    @ParameterizedTest(name = "from {0} to {1} should be {2}")
    @CsvSource({
        "PENDING,   CONFIRMED, true",
        "PENDING,   CANCELLED, true",
        "PENDING,   EXPIRED,   true",
        "PENDING,   PENDING,   false",
        "PENDING,   COMPLETED, false",
        "CONFIRMED, COMPLETED, true",
        "CONFIRMED, CANCELLED, true",
        "CONFIRMED, PENDING,   false",
        "CONFIRMED, CONFIRMED, false",
        "CONFIRMED, EXPIRED,   false",
        "CANCELLED, PENDING,   false",
        "CANCELLED, CONFIRMED, false",
        "CANCELLED, CANCELLED, false",
        "CANCELLED, EXPIRED,   false",
        "CANCELLED, COMPLETED, false",
        "EXPIRED,   PENDING,   false",
        "EXPIRED,   CONFIRMED, false",
        "EXPIRED,   CANCELLED, false",
        "EXPIRED,   EXPIRED,   false",
        "EXPIRED,   COMPLETED, false",
        "COMPLETED, PENDING,   false",
        "COMPLETED, CONFIRMED, false",
        "COMPLETED, CANCELLED, false",
        "COMPLETED, EXPIRED,   false",
        "COMPLETED, COMPLETED, false"
    })
    void testParameterizedTransitions(ReservationStatus from, ReservationStatus to, boolean expected) {
        assertThat(from.canTransitionTo(to)).isEqualTo(expected);
    }

    @Test
    @DisplayName("Transition to null should safely return false")
    void testTransitionToNull() {
        for (ReservationStatus status : ReservationStatus.values()) {
            assertThat(status.canTransitionTo(null)).isFalse();
        }
    }

    @Test
    @DisplayName("Verify terminal states and allowed sets")
    void testTerminalStatesAndAllowedSets() {
        assertThat(ReservationStatus.PENDING.isTerminal()).isFalse();
        assertThat(ReservationStatus.PENDING.allowedTransitions())
            .containsExactlyInAnyOrder(ReservationStatus.CONFIRMED, ReservationStatus.CANCELLED, ReservationStatus.EXPIRED);

        assertThat(ReservationStatus.CONFIRMED.isTerminal()).isFalse();
        assertThat(ReservationStatus.CONFIRMED.allowedTransitions())
            .containsExactlyInAnyOrder(ReservationStatus.COMPLETED, ReservationStatus.CANCELLED);

        assertThat(ReservationStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(ReservationStatus.CANCELLED.allowedTransitions()).isEmpty();

        assertThat(ReservationStatus.EXPIRED.isTerminal()).isTrue();
        assertThat(ReservationStatus.EXPIRED.allowedTransitions()).isEmpty();

        assertThat(ReservationStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(ReservationStatus.COMPLETED.allowedTransitions()).isEmpty();
    }
}
