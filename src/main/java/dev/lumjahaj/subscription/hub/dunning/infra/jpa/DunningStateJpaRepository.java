package dev.lumjahaj.subscription.hub.dunning.infra.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DunningStateJpaRepository extends JpaRepository<DunningStateEntity, UUID> {

    Optional<DunningStateEntity> findByTenantIdAndInvoiceId(String tenantId, UUID invoiceId);

    List<DunningStateEntity> findByTenantIdAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            String tenantId, Instant cutoff);
}
