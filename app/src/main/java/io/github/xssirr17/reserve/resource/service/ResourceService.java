package io.github.xssirr17.reserve.resource.service;

import io.github.xssirr17.reserve.common.error.NotFoundException;
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

    public ResourceService(ResourceRepository resourceRepository) {
        this.resourceRepository = resourceRepository;
    }

    @Transactional
    public ResourceResponse createResource(CreateResourceRequest request) {
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
        Page<Resource> page = (type != null && !type.isBlank())
            ? resourceRepository.findByType(type, pageable)
            : resourceRepository.findAll(pageable);
        return page.map(ResourceResponse::from);
    }
}
