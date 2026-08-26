package dev.lumjahaj.subscription.hub.subscription.api;

import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionCreateRequest;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionResponse;
import dev.lumjahaj.subscription.hub.subscription.api.mapper.SubscriptionMapper;
import dev.lumjahaj.subscription.hub.subscription.app.SubscriptionService;
import dev.lumjahaj.subscription.hub.subscription.infra.jpa.SubscriptionEntity;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @PostMapping
    public ResponseEntity<SubscriptionResponse> create(@Valid @RequestBody SubscriptionCreateRequest request) {
        SubscriptionEntity created = subscriptionService.create(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(SubscriptionMapper.toResponse(created));
    }

    @GetMapping
    public ResponseEntity<Page<SubscriptionResponse>> list(
            @RequestParam(required = false) UUID customerId,
            Pageable pageable
    ) {
        Page<SubscriptionEntity> page = customerId != null
                ? subscriptionService.listByCustomer(customerId, pageable)
                : subscriptionService.list(pageable);
        return ResponseEntity.ok(page.map(SubscriptionMapper::toResponse));
    }
}
