package dev.lumjahaj.subscription.hub.catalog.app;

import dev.lumjahaj.subscription.hub.audit.app.AuditService;
import dev.lumjahaj.subscription.hub.audit.domain.AuditEventType;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanEntitlementCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.mapper.PlanEntitlementMapper;
import dev.lumjahaj.subscription.hub.catalog.domain.PlanEntitlementRepository;
import dev.lumjahaj.subscription.hub.catalog.domain.PlanRepository;
import dev.lumjahaj.subscription.hub.catalog.infra.jpa.PlanEntitlementEntity;
import dev.lumjahaj.subscription.hub.catalog.infra.jpa.PlanEntity;
import dev.lumjahaj.subscription.hub.common.api.ResourceAlreadyExistsException;
import dev.lumjahaj.subscription.hub.common.api.ResourceNotFoundException;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class PlanEntitlementService {

    private final PlanEntitlementRepository entitlements;
    private final PlanRepository plans;
    private final PlanEntitlementMapper mapper;
    private final AuditService audit;

    public PlanEntitlementService(
            PlanEntitlementRepository entitlements,
            PlanRepository plans,
            PlanEntitlementMapper mapper,
            AuditService audit
    ) {
        this.entitlements = entitlements;
        this.plans = plans;
        this.mapper = mapper;
        this.audit = audit;
    }

    @Transactional
    public PlanEntitlementEntity create(String planCode, PlanEntitlementCreateRequest request) {
        String tenantId = TenantContext.getTenantId();

        PlanEntity plan = plans.findByTenantIdAndCode(tenantId, planCode)
                .orElseThrow(() -> new ResourceNotFoundException("Plan", planCode));

        entitlements.findByTenantIdAndPlanIdAndKey(tenantId, plan.getId(), request.key())
                .ifPresent(existing -> {
                    throw new ResourceAlreadyExistsException("PlanEntitlement", request.key());
                });

        PlanEntitlementEntity entity = mapper.toEntity(request);
        entity.setTenantId(tenantId);
        entity.setPlan(plan);
        PlanEntitlementEntity saved = entitlements.save(entity);
        audit.record(AuditEventType.PLAN_ENTITLEMENT_CREATED, saved.getId(), Map.of(
                "planCode", planCode,
                "key", saved.getKey(),
                "value", request.value()));
        return saved;
    }

    public List<PlanEntitlementEntity> list(String planCode) {
        String tenantId = TenantContext.getTenantId();

        PlanEntity plan = plans.findByTenantIdAndCode(tenantId, planCode)
                .orElseThrow(() -> new ResourceNotFoundException("Plan", planCode));

        return entitlements.findByTenantIdAndPlanId(tenantId, plan.getId());
    }
}
