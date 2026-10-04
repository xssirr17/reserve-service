package io.github.xssirr17.reserve.resource.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record CreateResourceRequest(@NotBlank String name, @NotBlank String type, @NotNull Map<String, Object> metadata) {
}
