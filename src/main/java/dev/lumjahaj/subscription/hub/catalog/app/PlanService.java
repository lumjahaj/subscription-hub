package dev.lumjahaj.subscription.hub.catalog.app;

import dev.lumjahaj.subscription.hub.audit.app.AuditService;
import dev.lumjahaj.subscription.hub.audit.domain.AuditEventType;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.mapper.PlanMapper;
import dev.lumjahaj.subscription.hub.catalog.domain.PlanRepository;
import dev.lumjahaj.subscription.hub.catalog.domain.ProductRepository;
import dev.lumjahaj.subscription.hub.catalog.infra.jpa.PlanEntity;
import dev.lumjahaj.subscription.hub.catalog.infra.jpa.ProductEntity;
import dev.lumjahaj.subscription.hub.common.api.ResourceAlreadyExistsException;
import dev.lumjahaj.subscription.hub.common.api.ResourceNotFoundException;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class PlanService {

    private final PlanRepository plans;
    private final ProductRepository products;
    private final AuditService audit;

    public PlanService(PlanRepository plans, ProductRepository products, AuditService audit) {
        this.plans = plans;
        this.products = products;
        this.audit = audit;
    }

    @Transactional
    public PlanEntity create(PlanCreateRequest request) {
        String tenantId = TenantContext.getTenantId();

        // Resolve productCode -> ProductEntity here, in the service —
        // the mapper has no repository access by design (see PlanMapper).
        ProductEntity product = products.findByTenantIdAndCode(tenantId, request.productCode())
                .orElseThrow(() -> new ResourceNotFoundException("Product", request.productCode()));

        plans.findByTenantIdAndCode(tenantId, request.code())
                .ifPresent(existing -> {
                    throw new ResourceAlreadyExistsException("Plan", request.code());
                });

        PlanEntity entity = PlanMapper.toEntity(request);
        entity.setTenantId(tenantId);
        entity.setProduct(product);
        PlanEntity saved = plans.save(entity);
        // The price as it was set. Plans have no update endpoint yet, but
        // "what did this plan cost, and who set it" is exactly what an audit
        // log is for once they do.
        audit.record(AuditEventType.PLAN_CREATED, saved.getId(), Map.of(
                "code", saved.getCode(),
                "productCode", product.getCode(),
                "amountCents", saved.getAmountCents(),
                "currency", saved.getCurrency(),
                "intervalUnit", saved.getIntervalUnit(),
                "intervalCount", saved.getIntervalCount(),
                "trialDays", saved.getTrialDays()));
        return saved;
    }

    public Page<PlanEntity> list(Pageable pageable) {
        String tenantId = TenantContext.getTenantId();
        return plans.findByTenantId(tenantId, pageable);
    }

    public PlanEntity getByCode(String code) {
        String tenantId = TenantContext.getTenantId();
        return plans.findByTenantIdAndCode(tenantId, code)
                .orElseThrow(() -> new ResourceNotFoundException("Plan", code));
    }
}