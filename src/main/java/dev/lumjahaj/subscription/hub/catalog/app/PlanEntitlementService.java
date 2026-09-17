package dev.lumjahaj.subscription.hub.catalog.app;

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

@Service
public class PlanEntitlementService {

    private final PlanEntitlementRepository entitlements;
    private final PlanRepository plans;
    private final PlanEntitlementMapper mapper;

    public PlanEntitlementService(
            PlanEntitlementRepository entitlements,
            PlanRepository plans,
            PlanEntitlementMapper mapper
    ) {
        this.entitlements = entitlements;
        this.plans = plans;
        this.mapper = mapper;
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
        return entitlements.save(entity);
    }

    public List<PlanEntitlementEntity> list(String planCode) {
        String tenantId = TenantContext.getTenantId();

        PlanEntity plan = plans.findByTenantIdAndCode(tenantId, planCode)
                .orElseThrow(() -> new ResourceNotFoundException("Plan", planCode));

        return entitlements.findByTenantIdAndPlanId(tenantId, plan.getId());
    }
}
