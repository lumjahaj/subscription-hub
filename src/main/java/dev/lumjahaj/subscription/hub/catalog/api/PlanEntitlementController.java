package dev.lumjahaj.subscription.hub.catalog.api;

import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanEntitlementCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanEntitlementResponse;
import dev.lumjahaj.subscription.hub.catalog.api.mapper.PlanEntitlementMapper;
import dev.lumjahaj.subscription.hub.catalog.app.PlanEntitlementService;
import dev.lumjahaj.subscription.hub.catalog.infra.jpa.PlanEntitlementEntity;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/plans/{planCode}/entitlements")
public class PlanEntitlementController {

    private final PlanEntitlementService entitlementService;
    private final PlanEntitlementMapper mapper;

    public PlanEntitlementController(PlanEntitlementService entitlementService, PlanEntitlementMapper mapper) {
        this.entitlementService = entitlementService;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<PlanEntitlementResponse> create(
            @PathVariable String planCode,
            @Valid @RequestBody PlanEntitlementCreateRequest request
    ) {
        PlanEntitlementEntity created = entitlementService.create(planCode, request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(mapper.toResponse(created));
    }

    @GetMapping
    public ResponseEntity<List<PlanEntitlementResponse>> list(@PathVariable String planCode) {
        List<PlanEntitlementResponse> response = entitlementService.list(planCode).stream()
                .map(mapper::toResponse)
                .toList();
        return ResponseEntity.ok(response);
    }
}
