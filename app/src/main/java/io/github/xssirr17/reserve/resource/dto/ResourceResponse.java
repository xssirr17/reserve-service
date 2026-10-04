package io.github.xssirr17.reserve.resource.dto;

import io.github.xssirr17.reserve.resource.domain.Resource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ResourceResponse(
    UUID id,
    String name,
    String type,
    Map<String, Object> metadata,
    Instant createdAt,
    Instant updatedAt
) {
    public static ResourceResponse from(Resource resource) {
        return new ResourceResponse(
            resource.getId(),
            resource.getName(),
            resource.getType(),
            resource.getMetadata(),
            resource.getCreatedAt(),
            resource.getUpdatedAt()
        );
    }
}
