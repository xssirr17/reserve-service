package io.github.xssirr17.reserve.slot.api;

import io.github.xssirr17.reserve.slot.dto.CreateSlotRequest;
import io.github.xssirr17.reserve.slot.dto.SlotResponse;
import io.github.xssirr17.reserve.slot.service.SlotService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/resources/{resourceId}")
public class SlotController {

    private final SlotService slotService;

    public SlotController(SlotService slotService) {
        this.slotService = slotService;
    }

    @PostMapping("/slots")
    public ResponseEntity<SlotResponse> createSlot(
        @PathVariable UUID resourceId,
        @RequestBody @Valid CreateSlotRequest request
    ) {
        SlotResponse response = slotService.createSlot(resourceId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(response.id())
            .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/availability")
    public ResponseEntity<Page<SlotResponse>> getAvailability(
        @PathVariable UUID resourceId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
        Pageable pageable
    ) {
        return ResponseEntity.ok(slotService.getAvailability(resourceId, from, to, pageable));
    }
}
