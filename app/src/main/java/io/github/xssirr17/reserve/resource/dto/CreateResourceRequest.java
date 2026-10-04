package io.github.xssirr17.reserve.resource.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record CreateResourceRequest(
    @NotBlank String name,
    @NotBlank String type,
    @NotNull @Size(max = 100, message = "Metadata cannot contain more than 100 entries") Map<String, Object> metadata
) {
    public static final int MAX_METADATA_ENTRIES = 100;
    public static final int MAX_METADATA_BYTES = 16 * 1024; // 16 KB
}
