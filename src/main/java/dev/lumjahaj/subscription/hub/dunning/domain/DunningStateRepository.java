package dev.lumjahaj.subscription.hub.dunning.domain;

import dev.lumjahaj.subscription.hub.dunning.infra.jpa.DunningStateEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DunningStateRepository {

    DunningStateEntity save(DunningStateEntity state);

    Optional<DunningStateEntity> findByTenantIdAndInvoiceId(String tenantId, UUID invoiceId);

    List<DunningStateEntity> findByTenantIdAndNextAttemptAtLessThanEqual(String tenantId, Instant cutoff);

    /** Called when an invoice settles: the schedule has nothing left to say. */
    void delete(DunningStateEntity state);
}
