package dev.lumjahaj.subscription.hub.catalog.api;

import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanResponse;
import dev.lumjahaj.subscription.hub.catalog.api.mapper.PlanMapper;
import dev.lumjahaj.subscription.hub.catalog.app.PlanService;
import dev.lumjahaj.subscription.hub.catalog.infra.jpa.PlanEntity;
import dev.lumjahaj.subscription.hub.common.api.PagedResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

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
        URI location = UriComponentsBuilder.fromPath("/api/plans/{code}")
                .buildAndExpand(created.getCode())
                .toUri();
        return ResponseEntity.created(location).body(PlanMapper.toResponse(created));
    }

    @GetMapping("/{code}")
    public ResponseEntity<PlanResponse> getByCode(@PathVariable String code) {
        PlanEntity plan = planService.getByCode(code);
        return ResponseEntity.ok(PlanMapper.toResponse(plan));
    }

    @GetMapping
    public ResponseEntity<PagedResponse<PlanResponse>> list(Pageable pageable) {
        Page<PlanEntity> page = planService.list(pageable);
        return ResponseEntity.ok(PagedResponse.from(page, PlanMapper::toResponse));
    }
}