package dev.lumjahaj.subscription.hub.catalog.app;

import dev.lumjahaj.subscription.hub.audit.app.AuditService;
import dev.lumjahaj.subscription.hub.audit.domain.AuditEventType;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.mapper.ProductMapper;
import dev.lumjahaj.subscription.hub.catalog.domain.ProductRepository;
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
public class ProductService {

    private final ProductRepository products;
    private final AuditService audit;

    public ProductService(ProductRepository products, AuditService audit) {
        this.products = products;
        this.audit = audit;
    }

    @Transactional
    public ProductEntity create(ProductCreateRequest request) {
        String tenantId = TenantContext.getTenantId();

        // Enforce (tenant_id, code) uniqueness at the application level too,
        // not just relying on the DB constraint — lets us return a clean
        // 409 Conflict instead of surfacing a raw DataIntegrityViolationException.
        products.findByTenantIdAndCode(tenantId, request.code())
                .ifPresent(existing -> {
                    throw new ResourceAlreadyExistsException("Product", request.code());
                });

        ProductEntity entity = ProductMapper.toEntity(request);
        entity.setTenantId(tenantId);
        ProductEntity saved = products.save(entity);
        audit.record(AuditEventType.PRODUCT_CREATED, saved.getId(), Map.of("code", saved.getCode()));
        return saved;
    }

    public Page<ProductEntity> list(Pageable pageable) {
        String tenantId = TenantContext.getTenantId();
        return products.findByTenantId(tenantId, pageable);
    }

    public ProductEntity getByCode(String code) {
        String tenantId = TenantContext.getTenantId();
        return products.findByTenantIdAndCode(tenantId, code)
                .orElseThrow(() -> new ResourceNotFoundException("Product", code));
    }
}