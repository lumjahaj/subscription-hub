package dev.lumjahaj.subscription.hub.catalog.api;

import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanResponse;
import dev.lumjahaj.subscription.hub.catalog.api.mapper.PlanMapper;
import dev.lumjahaj.subscription.hub.catalog.app.PlanService;
import dev.lumjahaj.subscription.hub.catalog.infra.jpa.PlanEntity;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/plans")
public class PlanController {

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    @PostMapping
    public ResponseEntity<PlanResponse> create(@Valid @RequestBody PlanCreateRequest request) {
        PlanEntity created = planService.create(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(PlanMapper.toResponse(created));
    }

    @GetMapping
    public ResponseEntity<Page<PlanResponse>> list(Pageable pageable) {
        Page<PlanEntity> page = planService.list(pageable);
        return ResponseEntity.ok(page.map(PlanMapper::toResponse));
    }
}