package io.github.xssirr17.reserve.slot.api;

import io.github.xssirr17.reserve.common.error.GlobalExceptionHandler;
import io.github.xssirr17.reserve.slot.service.SlotService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SlotController.class)
@Import(GlobalExceptionHandler.class)
class SlotControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SlotService slotService;

    @Test
    @DisplayName("Availability with Z format succeeds")
    void testAvailabilityWithZFormat() throws Exception {
        UUID resourceId = UUID.randomUUID();
        when(slotService.getAvailability(eq(resourceId), any(Instant.class), any(Instant.class), any()))
            .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/resources/{resourceId}/availability", resourceId)
                .param("from", "2026-10-05T10:00:00Z")
                .param("to", "2026-10-05T12:00:00Z"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Availability with offset (+04:00) succeeds when parsed by Spring")
    void testAvailabilityWithEncodedOffset() throws Exception {
        UUID resourceId = UUID.randomUUID();
        when(slotService.getAvailability(eq(resourceId), any(Instant.class), any(Instant.class), any()))
            .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/resources/{resourceId}/availability", resourceId)
                .param("from", "2026-10-05T10:00:00+04:00")
                .param("to", "2026-10-05T12:00:00+04:00"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Availability with unencoded plus (decoded as space) fails with 400 ProblemDetail")
    void testAvailabilityWithUnencodedPlusFails() throws Exception {
        UUID resourceId = UUID.randomUUID();

        // When a client sends unencoded '+', HTTP decoders parse it as ' ', producing an invalid Instant string
        mockMvc.perform(get("/api/resources/{resourceId}/availability", resourceId)
                .param("from", "2026-10-05T10:00:00 04:00")
                .param("to", "2026-10-05T12:00:00Z"))
            .andExpect(status().isBadRequest());
    }
}
