package io.github.xssirr17.reserve.resource.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;
import io.github.xssirr17.reserve.resource.service.ResourceService;
import io.github.xssirr17.reserve.resource.dto.CreateResourceRequest;

@RestController
@RequestMapping("/api/resources")
public class ResourceController {
    private final ResourceService resourceService;

    public ResourceController(ResourceService resourceService){
        this.resourceService = resourceService;
    }

    @PostMapping
    public ResponseEntity<String> createResource(@RequestBody @Valid CreateResourceRequest request) {
        // this.ResourceService.createResource(request.name(), request.type(), request.metadata());
        return ResponseEntity.ok("success");
    }
}

