package dev.lumjahaj.subscription.hub.customer.app;

import dev.lumjahaj.subscription.hub.common.api.ResourceAlreadyExistsException;
import dev.lumjahaj.subscription.hub.common.api.ResourceNotFoundException;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerCreateRequest;
import dev.lumjahaj.subscription.hub.customer.api.mapper.CustomerMapper;
import dev.lumjahaj.subscription.hub.customer.domain.CustomerRepository;
import dev.lumjahaj.subscription.hub.customer.infra.jpa.CustomerEntity;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.UUID;

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

    public CustomerEntity getById(UUID id) {
        String tenantId = TenantContext.getTenantId();
        return customers.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", id.toString()));
    }

    /**
     * Stores the token automatic collection will charge. Replacing an
     * existing one is a plain overwrite, not a conflict: a customer whose
     * card expired sends the new token to the same endpoint.
     *
     * The old token is not kept. Nothing can be done with a superseded
     * provider token, and keeping payment credentials around after they
     * stop being needed is how they end up somewhere they shouldn't be.
     */
    public CustomerEntity setDefaultPaymentMethod(UUID id, String paymentMethod) {
        CustomerEntity customer = getById(id);
        customer.setDefaultPaymentMethod(paymentMethod);
        return customers.save(customer);
    }

    /**
     * Clearing is idempotent — a customer with no stored method stays that
     * way rather than 404ing on the second call — because the resource
     * being cleared is a field, not a row.
     */
    public CustomerEntity clearDefaultPaymentMethod(UUID id) {
        CustomerEntity customer = getById(id);
        customer.setDefaultPaymentMethod(null);
        return customers.save(customer);
    }
}
