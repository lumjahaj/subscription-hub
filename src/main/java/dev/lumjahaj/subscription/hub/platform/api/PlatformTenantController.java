package dev.lumjahaj.subscription.hub.platform.api;

import dev.lumjahaj.subscription.hub.audit.api.IncompleteAuditFilterException;
import dev.lumjahaj.subscription.hub.audit.api.dto.AuditEventResponse;
import dev.lumjahaj.subscription.hub.audit.api.mapper.AuditEventMapper;
import dev.lumjahaj.subscription.hub.audit.app.AuditService;
import dev.lumjahaj.subscription.hub.audit.domain.AuditEntityType;
import dev.lumjahaj.subscription.hub.auth.api.Authorize;
import dev.lumjahaj.subscription.hub.common.api.PagedResponse;
import dev.lumjahaj.subscription.hub.platform.api.dto.TenantCreateRequest;
import dev.lumjahaj.subscription.hub.platform.api.dto.TenantProvisionedResponse;
import dev.lumjahaj.subscription.hub.platform.api.dto.TenantResponse;
import dev.lumjahaj.subscription.hub.platform.api.mapper.TenantMapper;
import dev.lumjahaj.subscription.hub.platform.app.ProvisionedTenant;
import dev.lumjahaj.subscription.hub.platform.app.TenantProvisioningService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * The API an admin dashboard would call. There is no dashboard: this is a
 * JSON API, and requests/platform.http stands in for one.
 *
 * <p>Every method is annotated, reads included — the one controller where
 * "an unannotated read is deliberate" (see Authorize) does not apply,
 * because listing every tenant on the platform is itself privileged.
 * SecurityConfig already enforces the role on the path; the annotation
 * keeps the rule attached to the method if the path ever changes.
 *
 * <p>Activation is two POST sub-resources rather than a PATCH of
 * {@code active}: they are commands with consequences (every token of the
 * tenant stops working), not field edits, and there is no general update
 * endpoint for them to hide inside.
 */
@RestController
@RequestMapping("/api/platform/tenants")
public class PlatformTenantController {

    private final TenantProvisioningService provisioningService;
    private final AuditService auditService;
    private final AuditEventMapper auditEventMapper;

    public PlatformTenantController(
            TenantProvisioningService provisioningService,
            AuditService auditService,
            AuditEventMapper auditEventMapper
    ) {
        this.provisioningService = provisioningService;
        this.auditService = auditService;
        this.auditEventMapper = auditEventMapper;
    }

    @PostMapping
    @PreAuthorize(Authorize.PLATFORM_ADMIN)
    public ResponseEntity<TenantProvisionedResponse> create(@Valid @RequestBody TenantCreateRequest request) {
        ProvisionedTenant provisioned = provisioningService.provision(request);
        URI location = UriComponentsBuilder.fromPath("/api/platform/tenants/{id}")
                .buildAndExpand(provisioned.tenant().id())
                .toUri();
        return ResponseEntity.created(location).body(TenantMapper.toResponse(provisioned));
    }

    @GetMapping
    @PreAuthorize(Authorize.PLATFORM_ADMIN)
    public ResponseEntity<PagedResponse<TenantResponse>> list(@PageableDefault(sort = "id") Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.from(provisioningService.list(pageable), TenantMapper::toResponse));
    }

    @GetMapping("/{id}")
    @PreAuthorize(Authorize.PLATFORM_ADMIN)
    public ResponseEntity<TenantResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(TenantMapper.toResponse(provisioningService.get(id)));
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize(Authorize.PLATFORM_ADMIN)
    public ResponseEntity<TenantResponse> deactivate(@PathVariable String id) {
        return ResponseEntity.ok(TenantMapper.toResponse(provisioningService.deactivate(id)));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize(Authorize.PLATFORM_ADMIN)
    public ResponseEntity<TenantResponse> activate(@PathVariable String id) {
        return ResponseEntity.ok(TenantMapper.toResponse(provisioningService.activate(id)));
    }

    /**
     * A tenant's whole audit log, as seen from the platform. This needs none
     * of PaymentWebhookService's session handling, although the request has
     * no tenant, because audit_event deliberately has no @TenantId (see
     * AuditEventEntity).
     *
     * An unknown tenant is a 404 rather than an empty page, so a typo in the
     * id cannot look like a tenant that did nothing.
     */
    @GetMapping("/{id}/audit-events")
    @PreAuthorize(Authorize.PLATFORM_ADMIN)
    public ResponseEntity<PagedResponse<AuditEventResponse>> auditEvents(
            @PathVariable String id,
            @RequestParam(required = false) AuditEntityType entityType,
            @RequestParam(required = false) String entityId,
            Pageable pageable
    ) {
        if ((entityType == null) != (entityId == null)) {
            throw new IncompleteAuditFilterException();
        }
        provisioningService.get(id);
        var page = auditService.list(id, entityType, entityId, pageable);
        return ResponseEntity.ok(PagedResponse.from(page, auditEventMapper::toResponse));
    }
}
