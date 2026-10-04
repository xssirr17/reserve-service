package io.github.xssirr17.reserve.reservation.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xssirr17.reserve.common.error.GlobalExceptionHandler;
import io.github.xssirr17.reserve.reservation.service.ReservationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReservationController.class)
@Import(GlobalExceptionHandler.class)
class ReservationControllerValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ReservationService reservationService;

    @MockBean
    private io.github.xssirr17.reserve.saga.ReservationSagaCoordinator sagaCoordinator;

    @Test
    @DisplayName("Quantity Integer.MAX_VALUE must return 400 Bad Request ProblemDetail")
    void testQuantityMaxIntReturns400() throws Exception {
        UUID slotId = UUID.randomUUID();
        String json = """
            {
                "slotId": "%s",
                "userId": "user-123",
                "quantity": 2147483647
            }
            """.formatted(slotId);

        mockMvc.perform(post("/api/reservations")
                .header("Idempotency-Key", "key-max-int")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title", is("Bad Request")))
            .andExpect(jsonPath("$.status", is(400)))
            .andExpect(jsonPath("$.type", is("urn:problem-type:validation-error")))
            .andExpect(jsonPath("$.errors.quantity", notNullValue()));
    }

    @Test
    @DisplayName("Quantity 0 must return 400 Bad Request ProblemDetail")
    void testQuantityZeroReturns400() throws Exception {
        UUID slotId = UUID.randomUUID();
        String json = """
            {
                "slotId": "%s",
                "userId": "user-123",
                "quantity": 0
            }
            """.formatted(slotId);

        mockMvc.perform(post("/api/reservations")
                .header("Idempotency-Key", "key-zero")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title", is("Bad Request")))
            .andExpect(jsonPath("$.status", is(400)))
            .andExpect(jsonPath("$.type", is("urn:problem-type:validation-error")))
            .andExpect(jsonPath("$.errors.quantity", notNullValue()));
    }

    @Test
    @DisplayName("Quantity negative must return 400 Bad Request ProblemDetail")
    void testQuantityNegativeReturns400() throws Exception {
        UUID slotId = UUID.randomUUID();
        String json = """
            {
                "slotId": "%s",
                "userId": "user-123",
                "quantity": -5
            }
            """.formatted(slotId);

        mockMvc.perform(post("/api/reservations")
                .header("Idempotency-Key", "key-negative")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title", is("Bad Request")))
            .andExpect(jsonPath("$.status", is(400)))
            .andExpect(jsonPath("$.type", is("urn:problem-type:validation-error")))
            .andExpect(jsonPath("$.errors.quantity", notNullValue()));
    }

    @Test
    @DisplayName("Quantity string overflow beyond Long must return 400 Bad Request malformed JSON ProblemDetail")
    void testQuantityOverflowReturns400() throws Exception {
        UUID slotId = UUID.randomUUID();
        String json = """
            {
                "slotId": "%s",
                "userId": "user-123",
                "quantity": 99999999999999999999999999999999999999999
            }
            """.formatted(slotId);

        mockMvc.perform(post("/api/reservations")
                .header("Idempotency-Key", "key-overflow")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title", is("Bad Request")))
            .andExpect(jsonPath("$.status", is(400)))
            .andExpect(jsonPath("$.type", is("urn:problem-type:malformed-request")));
    }

    @Test
    @DisplayName("GET /api/reservations?userId=... returns 200 and page of reservations")
    void testListReservationsValidUser() throws Exception {
        when(reservationService.listByUser(org.mockito.ArgumentMatchers.eq("user-123"), any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of()));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/reservations")
                .param("userId", "user-123")
                .param("page", "0")
                .param("size", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content", notNullValue()));
    }

    @Test
    @DisplayName("GET /api/reservations without userId returns 400 Bad Request ProblemDetail")
    void testListReservationsMissingUserIdReturns400() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/reservations"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/reservations with blank userId returns 400 Bad Request ProblemDetail")
    void testListReservationsBlankUserIdReturns400() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/reservations")
                .param("userId", "   "))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title", is("Bad Request")))
            .andExpect(jsonPath("$.status", is(400)));
    }
}
