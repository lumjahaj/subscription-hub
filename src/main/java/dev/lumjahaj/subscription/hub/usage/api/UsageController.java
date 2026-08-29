package dev.lumjahaj.subscription.hub.usage.api;

import dev.lumjahaj.subscription.hub.usage.api.dto.UsageCounterResponse;
import dev.lumjahaj.subscription.hub.usage.api.dto.UsageRecordRequest;
import dev.lumjahaj.subscription.hub.usage.api.mapper.UsageCounterMapper;
import dev.lumjahaj.subscription.hub.usage.app.UsageService;
import dev.lumjahaj.subscription.hub.usage.infra.jpa.UsageCounterEntity;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/subscriptions/{subscriptionId}/usage")
public class UsageController {

    private final UsageService usageService;

    public UsageController(UsageService usageService) {
        this.usageService = usageService;
    }

    // 200, not the usual 201: this POST increments a running total rather
    // than creating a new resource each call - the first call for a given
    // meter+period creates the counter row, every later call for the same
    // one just adds to it, and the response is the same either way.
    @PostMapping
    public ResponseEntity<UsageCounterResponse> record(
            @PathVariable UUID subscriptionId,
            @Valid @RequestBody UsageRecordRequest request
    ) {
        UsageCounterEntity counter = usageService.record(subscriptionId, request);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(UsageCounterMapper.toResponse(counter));
    }

    @GetMapping
    public ResponseEntity<List<UsageCounterResponse>> list(@PathVariable UUID subscriptionId) {
        List<UsageCounterResponse> response = usageService.list(subscriptionId).stream()
                .map(UsageCounterMapper::toResponse)
                .toList();
        return ResponseEntity.ok(response);
    }
}
