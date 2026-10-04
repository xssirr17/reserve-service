package io.github.xssirr17.reserve.slot.service;

import io.github.xssirr17.reserve.common.error.ConflictException;
import io.github.xssirr17.reserve.common.error.NotFoundException;
import io.github.xssirr17.reserve.resource.domain.ResourceRepository;
import io.github.xssirr17.reserve.slot.domain.Slot;
import io.github.xssirr17.reserve.slot.domain.SlotRepository;
import io.github.xssirr17.reserve.slot.dto.CreateSlotRequest;
import io.github.xssirr17.reserve.slot.dto.SlotResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class SlotService {

    private static final Logger log = LoggerFactory.getLogger(SlotService.class);

    private final SlotRepository slotRepository;
    private final ResourceRepository resourceRepository;
    private final AvailabilityCacheService availabilityCacheService;
    private final Clock clock;

    public SlotService(SlotRepository slotRepository,
                       ResourceRepository resourceRepository,
                       AvailabilityCacheService availabilityCacheService,
                       Clock clock) {
        this.slotRepository = slotRepository;
        this.resourceRepository = resourceRepository;
        this.availabilityCacheService = availabilityCacheService;
        this.clock = clock;
    }

    @Transactional
    public SlotResponse createSlot(UUID resourceId, CreateSlotRequest request) {
        if (!resourceRepository.existsById(resourceId)) {
            throw new NotFoundException("Resource not found: " + resourceId);
        }

        if (request.startTime().isBefore(clock.instant())) {
            throw new ConflictException("Cannot create a slot in the past");
        }

        if (!request.endTime().isAfter(request.startTime())) {
            throw new IllegalArgumentException("endTime must be strictly after startTime");
        }

        if (request.capacity() <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }

        if (slotRepository.hasOverlappingSlot(resourceId, request.startTime(), request.endTime())) {
            throw new ConflictException("Slot overlaps with an existing slot for this resource");
        }

        try {
            Slot slot = new Slot(resourceId, request.startTime(), request.endTime(), request.capacity());
            Slot saved = slotRepository.save(slot);

            // Invalidate cached availability for this resource AFTER transaction commit
            availabilityCacheService.invalidateResourceAfterCommit(resourceId);

            return SlotResponse.from(saved);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Database conflict creating slot for resource {}: {}", resourceId, ex.getMessage());
            throw new ConflictException("Slot time window conflict or overlap for resource: " + resourceId, ex);
        }
    }

    @Transactional(readOnly = true)
    public Page<SlotResponse> getAvailability(UUID resourceId, Instant from, Instant to, Pageable pageable) {
        if (!resourceRepository.existsById(resourceId)) {
            throw new NotFoundException("Resource not found: " + resourceId);
        }

        return availabilityCacheService.getAvailability(resourceId, from, to, pageable, () ->
            slotRepository.findByResourceIdAndTimeRange(resourceId, from, to, pageable)
                .map(SlotResponse::from)
        );
    }
}
