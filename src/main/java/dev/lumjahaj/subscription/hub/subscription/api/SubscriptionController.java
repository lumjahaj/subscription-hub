package dev.lumjahaj.subscription.hub.subscription.api;

import dev.lumjahaj.subscription.hub.auth.api.Authorize;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionCreateRequest;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionPlanChangeRequest;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionResponse;
import dev.lumjahaj.subscription.hub.subscription.api.mapper.SubscriptionMapper;
import dev.lumjahaj.subscription.hub.subscription.app.SubscriptionService;
import dev.lumjahaj.subscription.hub.subscription.infra.jpa.SubscriptionEntity;
import dev.lumjahaj.subscription.hub.common.api.PagedResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @PostMapping
    @PreAuthorize(Authorize.COMMERCIAL)
    public ResponseEntity<SubscriptionResponse> create(@Valid @RequestBody SubscriptionCreateRequest request) {
        SubscriptionEntity created = subscriptionService.create(request);
        URI location = UriComponentsBuilder.fromPath("/api/subscriptions/{id}")
                .buildAndExpand(created.getId())
                .toUri();
        return ResponseEntity.created(location).body(SubscriptionMapper.toResponse(created));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SubscriptionResponse> getById(@PathVariable UUID id) {
        SubscriptionEntity subscription = subscriptionService.getById(id);
        return ResponseEntity.ok(SubscriptionMapper.toResponse(subscription));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(Authorize.COMMERCIAL)
    public ResponseEntity<SubscriptionResponse> cancel(@PathVariable UUID id) {
        SubscriptionEntity canceled = subscriptionService.cancel(id);
        return ResponseEntity.ok(SubscriptionMapper.toResponse(canceled));
    }

    @PostMapping("/{id}/pause")
    @PreAuthorize(Authorize.COMMERCIAL)
    public ResponseEntity<SubscriptionResponse> pause(@PathVariable UUID id) {
        SubscriptionEntity paused = subscriptionService.pause(id);
        return ResponseEntity.ok(SubscriptionMapper.toResponse(paused));
    }

    @PostMapping("/{id}/resume")
    @PreAuthorize(Authorize.COMMERCIAL)
    public ResponseEntity<SubscriptionResponse> resume(@PathVariable UUID id) {
        SubscriptionEntity resumed = subscriptionService.resume(id);
        return ResponseEntity.ok(SubscriptionMapper.toResponse(resumed));
    }

    /**
     * Schedules the plan change for the next renewal; re-sending it with a
     * different plan replaces the schedule, and sending the plan the
     * subscription is already on clears it.
     *
     * POST rather than PUT on a sub-resource, and no If-Match: this is an
     * idempotent state command, so a caller whose intent is already satisfied
     * gets the no-op rather than a precondition failure (CLAUDE.md §5).
     */
    @PostMapping("/{id}/change-plan")
    @PreAuthorize(Authorize.COMMERCIAL)
    public ResponseEntity<SubscriptionResponse> schedulePlanChange(
            @PathVariable UUID id,
            @Valid @RequestBody SubscriptionPlanChangeRequest request
    ) {
        SubscriptionEntity scheduled = subscriptionService.schedulePlanChange(id, request.planCode());
        return ResponseEntity.ok(SubscriptionMapper.toResponse(scheduled));
    }

    @DeleteMapping("/{id}/change-plan")
    @PreAuthorize(Authorize.COMMERCIAL)
    public ResponseEntity<SubscriptionResponse> cancelPlanChange(@PathVariable UUID id) {
        SubscriptionEntity cleared = subscriptionService.cancelPlanChange(id);
        return ResponseEntity.ok(SubscriptionMapper.toResponse(cleared));
    }

    @GetMapping
    public ResponseEntity<PagedResponse<SubscriptionResponse>> list(
            @RequestParam(required = false) UUID customerId,
            Pageable pageable
    ) {
        Page<SubscriptionEntity> page = customerId != null
                ? subscriptionService.listByCustomer(customerId, pageable)
                : subscriptionService.list(pageable);
        return ResponseEntity.ok(PagedResponse.from(page, SubscriptionMapper::toResponse));
    }
}
