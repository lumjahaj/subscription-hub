package dev.lumjahaj.subscription.hub.customer.infra.jpa;

import dev.lumjahaj.subscription.hub.customer.domain.CustomerRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class CustomerRepositoryImpl implements CustomerRepository {

    private final CustomerJpaRepository jpaRepository;

    public CustomerRepositoryImpl(CustomerJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public CustomerEntity save(CustomerEntity customer) {
        return jpaRepository.save(customer);
    }

    @Override
    public Optional<CustomerEntity> findByTenantIdAndEmail(String tenantId, String email) {
        return jpaRepository.findByTenantIdAndEmail(tenantId, email);
    }

    @Override
    public Page<CustomerEntity> findByTenantId(String tenantId, Pageable pageable) {
        return jpaRepository.findByTenantId(tenantId, pageable);
    }
}
