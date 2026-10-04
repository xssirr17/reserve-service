package io.github.xssirr17.reserve.common.error;

import io.github.xssirr17.reserve.reservation.domain.ReservationStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.hibernate.exception.JDBCConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.TestController.class)
@Import({GlobalExceptionHandler.class, GlobalExceptionHandlerTest.Config.class})
class GlobalExceptionHandlerTest {

    @TestConfiguration
    static class Config {
        @Bean
        TestController testController() {
            return new TestController();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @RestController
    @RequestMapping("/test/errors")
    static class TestController {

        @GetMapping("/not-found")
        public void notFound() {
            throw new NotFoundException("Resource 123 not found");
        }

        @GetMapping("/conflict")
        public void conflict() {
            throw new ConflictException("Slot already booked");
        }

        @GetMapping("/insufficient-capacity")
        public void insufficientCapacity() {
            throw new InsufficientCapacityException(UUID.randomUUID(), 5, 2);
        }

        @GetMapping("/invalid-transition")
        public void invalidTransition() {
            throw new InvalidStateTransitionException(ReservationStatus.CANCELLED, ReservationStatus.CONFIRMED);
        }

        @PostMapping("/validation")
        public void validation(@RequestBody @Valid DummyPayload payload) {
        }

        @GetMapping("/pool-exhaustion/cannot-create-tx")
        public void cannotCreateTx() {
            throw new CannotCreateTransactionException(
                "Could not open JPA EntityManager for transaction",
                new SQLTransientConnectionException("Connection is not available, request timed out after 5000ms.")
            );
        }

        @GetMapping("/pool-exhaustion/jdbc-conn")
        public void jdbcConnection() {
            throw new JDBCConnectionException("Unable to acquire JDBC Connection", new SQLException("Connection refused"));
        }

        @GetMapping("/pool-exhaustion/sql-transient")
        public void sqlTransient() throws SQLException {
            throw new SQLTransientConnectionException("HikariCP pool reserve-pool exhausted");
        }
    }

    record DummyPayload(
        @NotBlank String name,
        @NotNull Integer count
    ) {}

    @Test
    @DisplayName("NotFoundException returns 404 with ProblemDetail")
    void testNotFound() throws Exception {
        mockMvc.perform(get("/test/errors/not-found"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.title", is("Resource Not Found")))
            .andExpect(jsonPath("$.status", is(404)))
            .andExpect(jsonPath("$.detail", is("Resource 123 not found")))
            .andExpect(jsonPath("$.type", is("urn:problem-type:not-found")))
            .andExpect(jsonPath("$.timestamp", notNullValue()));
    }

    @Test
    @DisplayName("ConflictException returns 409 with ProblemDetail")
    void testConflict() throws Exception {
        mockMvc.perform(get("/test/errors/conflict"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.title", is("Conflict")))
            .andExpect(jsonPath("$.status", is(409)))
            .andExpect(jsonPath("$.detail", is("Slot already booked")))
            .andExpect(jsonPath("$.type", is("urn:problem-type:conflict")));
    }

    @Test
    @DisplayName("InsufficientCapacityException returns 409 with capacity properties")
    void testInsufficientCapacity() throws Exception {
        mockMvc.perform(get("/test/errors/insufficient-capacity"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.title", is("Insufficient Capacity")))
            .andExpect(jsonPath("$.status", is(409)))
            .andExpect(jsonPath("$.type", is("urn:problem-type:insufficient-capacity")))
            .andExpect(jsonPath("$.requested", is(5)))
            .andExpect(jsonPath("$.available", is(2)))
            .andExpect(jsonPath("$.slotId", notNullValue()));
    }

    @Test
    @DisplayName("InvalidStateTransitionException returns 409 with transition properties")
    void testInvalidStateTransition() throws Exception {
        mockMvc.perform(get("/test/errors/invalid-transition"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.title", is("Invalid State Transition")))
            .andExpect(jsonPath("$.status", is(409)))
            .andExpect(jsonPath("$.type", is("urn:problem-type:invalid-state-transition")))
            .andExpect(jsonPath("$.currentStatus", is("CANCELLED")))
            .andExpect(jsonPath("$.targetStatus", is("CONFIRMED")));
    }

    @Test
    @DisplayName("Validation error returns 400 with ProblemDetail and field error map")
    void testValidationError() throws Exception {
        mockMvc.perform(post("/test/errors/validation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title", is("Bad Request")))
            .andExpect(jsonPath("$.status", is(400)))
            .andExpect(jsonPath("$.type", is("urn:problem-type:validation-error")))
            .andExpect(jsonPath("$.errors.name", notNullValue()))
            .andExpect(jsonPath("$.errors.count", notNullValue()));
    }

    @Test
    @DisplayName("CannotCreateTransactionException returns 503 with Retry-After header")
    void testCannotCreateTransactionException() throws Exception {
        mockMvc.perform(get("/test/errors/pool-exhaustion/cannot-create-tx"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Retry-After", "2"))
            .andExpect(jsonPath("$.title", is("Service Unavailable")))
            .andExpect(jsonPath("$.status", is(503)))
            .andExpect(jsonPath("$.type", is("urn:problem-type:service-unavailable")))
            .andExpect(jsonPath("$.detail", is("Database connection temporarily unavailable, please retry shortly")))
            .andExpect(jsonPath("$.timestamp", notNullValue()));
    }

    @Test
    @DisplayName("JDBCConnectionException returns 503 with Retry-After header")
    void testJdbcConnectionException() throws Exception {
        mockMvc.perform(get("/test/errors/pool-exhaustion/jdbc-conn"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Retry-After", "2"))
            .andExpect(jsonPath("$.title", is("Service Unavailable")))
            .andExpect(jsonPath("$.status", is(503)))
            .andExpect(jsonPath("$.type", is("urn:problem-type:service-unavailable")))
            .andExpect(jsonPath("$.detail", is("Database connection temporarily unavailable, please retry shortly")));
    }

    @Test
    @DisplayName("SQLTransientConnectionException returns 503 with Retry-After header")
    void testSqlTransientConnectionException() throws Exception {
        mockMvc.perform(get("/test/errors/pool-exhaustion/sql-transient"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Retry-After", "2"))
            .andExpect(jsonPath("$.title", is("Service Unavailable")))
            .andExpect(jsonPath("$.status", is(503)))
            .andExpect(jsonPath("$.type", is("urn:problem-type:service-unavailable")))
            .andExpect(jsonPath("$.detail", is("Database connection temporarily unavailable, please retry shortly")));
    }
}
