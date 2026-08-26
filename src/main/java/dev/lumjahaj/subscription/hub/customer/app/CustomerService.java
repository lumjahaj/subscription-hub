package dev.lumjahaj.subscription.hub.customer.app;

import dev.lumjahaj.subscription.hub.common.api.ResourceAlreadyExistsException;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerCreateRequest;
import dev.lumjahaj.subscription.hub.customer.api.mapper.CustomerMapper;
import dev.lumjahaj.subscription.hub.customer.domain.CustomerRepository;
import dev.lumjahaj.subscription.hub.customer.infra.jpa.CustomerEntity;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class CustomerService {

    private final CustomerRepository customers;

    public CustomerService(CustomerRepository customers) {
        this.customers = customers;
    }

    public CustomerEntity create(CustomerCreateRequest request) {
        String tenantId = TenantContext.getTenantId();

        customers.findByTenantIdAndEmail(tenantId, request.email())
                .ifPresent(existing -> {
                    throw new ResourceAlreadyExistsException("Customer", request.email());
                });

        CustomerEntity entity = CustomerMapper.toEntity(request);
        entity.setTenantId(tenantId);
        return customers.save(entity);
    }

    public Page<CustomerEntity> list(Pageable pageable) {
        String tenantId = TenantContext.getTenantId();
        return customers.findByTenantId(tenantId, pageable);
    }
}
