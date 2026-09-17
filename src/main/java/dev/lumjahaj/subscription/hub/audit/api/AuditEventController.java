package dev.lumjahaj.subscription.hub.audit.api;

import dev.lumjahaj.subscription.hub.audit.api.dto.AuditEventResponse;
import dev.lumjahaj.subscription.hub.audit.api.mapper.AuditEventMapper;
import dev.lumjahaj.subscription.hub.audit.app.AuditService;
import dev.lumjahaj.subscription.hub.audit.domain.AuditEntityType;
import dev.lumjahaj.subscription.hub.auth.api.Authorize;
import dev.lumjahaj.subscription.hub.common.api.PagedResponse;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only by design. There is no endpoint to write, correct or delete an
 * audit event, and there never should be.
 */
@RestController
@RequestMapping("/api/audit-events")
public class AuditEventController {

    private final AuditService auditService;
    private final AuditEventMapper mapper;

    public AuditEventController(AuditService auditService, AuditEventMapper mapper) {
        this.auditService = auditService;
        this.mapper = mapper;
    }

    @GetMapping
    @PreAuthorize(Authorize.AUDIT_READ)
    public ResponseEntity<PagedResponse<AuditEventResponse>> list(
            @RequestParam(required = false) AuditEntityType entityType,
            @RequestParam(required = false) String entityId,
            Pageable pageable
    ) {
        if ((entityType == null) != (entityId == null)) {
            throw new IncompleteAuditFilterException();
        }
        var page = auditService.list(TenantContext.getTenantId(), entityType, entityId, pageable);
        return ResponseEntity.ok(PagedResponse.from(page, mapper::toResponse));
    }
}
