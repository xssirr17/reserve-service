package io.github.xssirr17.reserve;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xssirr17.reserve.common.error.InsufficientCapacityException;
import io.github.xssirr17.reserve.idempotency.IdempotencyKeyRepository;
import io.github.xssirr17.reserve.reservation.domain.ReservationRepository;
import io.github.xssirr17.reserve.reservation.dto.CreateReservationRequest;
import io.github.xssirr17.reserve.reservation.service.ReservationService;
import io.github.xssirr17.reserve.resource.domain.Resource;
import io.github.xssirr17.reserve.resource.domain.ResourceRepository;
import io.github.xssirr17.reserve.slot.domain.Slot;
import io.github.xssirr17.reserve.slot.domain.SlotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=${TEST_DB_URL:jdbc:postgresql://localhost:5432/reserve}",
    "spring.datasource.username=${TEST_DB_USERNAME:postgres}",
    "spring.datasource.password=${TEST_DB_PASSWORD:}",
    "spring.data.redis.host=${TEST_REDIS_HOST:localhost}",
    "spring.data.redis.port=${TEST_REDIS_PORT:6379}",
    "spring.datasource.hikari.maximum-pool-size=10",
    "spring.jpa.hibernate.ddl-auto=validate"
})
class IdempotencyLifecycleIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private SlotRepository slotRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private Resource testResource;
    private Slot testSlot;

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAll();
        slotRepository.deleteAll();
        resourceRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();

        testResource = resourceRepository.save(new Resource("Test Room", "ROOM", java.util.Map.of()));
        Instant start = Instant.now().plus(2, ChronoUnit.HOURS);
        Instant end = start.plus(1, ChronoUnit.HOURS);
        testSlot = slotRepository.save(new Slot(testResource.getId(), start, end, 1));
    }

    @Test
    @DisplayName("Retry after capacity error must not be stuck in 409 IN_PROGRESS")
    void retryAfterCapacityErrorMustNotBeStuckInProgress() throws Exception {
        // Fill capacity first
        testSlot.setReserved(1);
        slotRepository.save(testSlot);

        CreateReservationRequest request = new CreateReservationRequest(testSlot.getId(), "user-retry", 1);
        String idempotencyKey = "key-fail-retry-" + UUID.randomUUID();

        // 1st attempt: expect 409 Conflict due to InsufficientCapacityException
        mockMvc.perform(post("/api/reservations")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.title").value("Insufficient Capacity"));

        // 2nd attempt with same key: must NOT be 409 "A request with idempotency key ... is currently in progress"
        // It should either re-execute (and return 409 Insufficient Capacity) or replay cached failure, NEVER in-progress!
        mockMvc.perform(post("/api/reservations")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.title").value("Insufficient Capacity"));
    }

    @Test
    @DisplayName("Idempotency replay returns 201, identical Location header and identical body with same reservation ID")
    void replayReturnsSameStatusAndLocation() throws Exception {
        CreateReservationRequest request = new CreateReservationRequest(testSlot.getId(), "user-replay", 1);
        String idempotencyKey = "key-success-replay-" + UUID.randomUUID();

        // 1st call: expect 201 Created with Location header
        var result1 = mockMvc.perform(post("/api/reservations")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andReturn();

        String location1 = result1.getResponse().getHeader("Location");
        String body1 = result1.getResponse().getContentAsString();
        assertThat(location1).isNotBlank();

        // 2nd call with same Idempotency-Key: replay must return 201 Created + identical Location + identical body
        var result2 = mockMvc.perform(post("/api/reservations")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", location1))
            .andReturn();

        String body2 = result2.getResponse().getContentAsString();
        assertThat(body2).isEqualTo(body1);
    }
}
