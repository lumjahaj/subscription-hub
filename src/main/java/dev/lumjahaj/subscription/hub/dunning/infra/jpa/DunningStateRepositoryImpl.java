package dev.lumjahaj.subscription.hub.dunning.infra.jpa;

import dev.lumjahaj.subscription.hub.dunning.domain.DunningStateRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DunningStateRepositoryImpl implements DunningStateRepository {

    private final DunningStateJpaRepository jpaRepository;

    public DunningStateRepositoryImpl(DunningStateJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public DunningStateEntity save(DunningStateEntity state) {
        return jpaRepository.save(state);
    }

    @Override
    public Optional<DunningStateEntity> findByTenantIdAndInvoiceId(String tenantId, UUID invoiceId) {
        return jpaRepository.findByTenantIdAndInvoiceId(tenantId, invoiceId);
    }

    // Oldest due first, so a backlog is worked through in the order it built up.
    @Override
    public List<DunningStateEntity> findByTenantIdAndNextAttemptAtLessThanEqual(String tenantId, Instant cutoff) {
        return jpaRepository.findByTenantIdAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(tenantId, cutoff);
    }

    @Override
    public void delete(DunningStateEntity state) {
        jpaRepository.delete(state);
    }
}
