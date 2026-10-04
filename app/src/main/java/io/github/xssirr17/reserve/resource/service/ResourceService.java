package io.github.xssirr17.reserve.resource.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xssirr17.reserve.common.error.NotFoundException;
import io.github.xssirr17.reserve.common.util.PageUtils;
import io.github.xssirr17.reserve.resource.domain.Resource;
import io.github.xssirr17.reserve.resource.domain.ResourceRepository;
import io.github.xssirr17.reserve.resource.dto.CreateResourceRequest;
import io.github.xssirr17.reserve.resource.dto.ResourceResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ResourceService {

    private final ResourceRepository resourceRepository;
    private final ObjectMapper objectMapper;

    public ResourceService(ResourceRepository resourceRepository, ObjectMapper objectMapper) {
        this.resourceRepository = resourceRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ResourceResponse createResource(CreateResourceRequest request) {
        if (request.metadata() != null) {
            if (request.metadata().size() > CreateResourceRequest.MAX_METADATA_ENTRIES) {
                throw new IllegalArgumentException("Metadata cannot contain more than " + CreateResourceRequest.MAX_METADATA_ENTRIES + " entries");
            }
            try {
                byte[] bytes = objectMapper.writeValueAsBytes(request.metadata());
                if (bytes.length > CreateResourceRequest.MAX_METADATA_BYTES) {
                    throw new IllegalArgumentException("Metadata JSON size cannot exceed " + CreateResourceRequest.MAX_METADATA_BYTES + " bytes");
                }
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Invalid metadata: " + e.getMessage());
            }
        }
        Resource resource = new Resource(request.name(), request.type(), request.metadata());
        Resource saved = resourceRepository.save(resource);
        return ResourceResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public ResourceResponse getResource(UUID id) {
        return resourceRepository.findById(id)
            .map(ResourceResponse::from)
            .orElseThrow(() -> new NotFoundException("Resource not found: " + id));
    }

    @Transactional(readOnly = true)
    public Page<ResourceResponse> listResources(String type, Pageable pageable) {
        Pageable clamped = PageUtils.clamp(pageable);
        Page<Resource> page = (type != null && !type.isBlank())
            ? resourceRepository.findByType(type, clamped)
            : resourceRepository.findAll(clamped);
        return page.map(ResourceResponse::from);
    }
}
