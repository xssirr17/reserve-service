package io.github.xssirr17.reserve.resource.api;

import io.github.xssirr17.reserve.resource.dto.CreateResourceRequest;
import io.github.xssirr17.reserve.resource.dto.ResourceResponse;
import io.github.xssirr17.reserve.resource.service.ResourceService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
import java.util.UUID;

@RestController
@RequestMapping("/api/resources")
public class ResourceController {

    private final ResourceService resourceService;

    public ResourceController(ResourceService resourceService) {
        this.resourceService = resourceService;
    }

    @PostMapping
    public ResponseEntity<ResourceResponse> createResource(@RequestBody @Valid CreateResourceRequest request) {
        ResourceResponse response = resourceService.createResource(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(response.id())
            .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ResourceResponse> getResource(@PathVariable UUID id) {
        return ResponseEntity.ok(resourceService.getResource(id));
    }

    @GetMapping
    public ResponseEntity<Page<ResourceResponse>> listResources(
        @RequestParam(required = false) String type,
        Pageable pageable
    ) {
        return ResponseEntity.ok(resourceService.listResources(type, pageable));
    }
}
