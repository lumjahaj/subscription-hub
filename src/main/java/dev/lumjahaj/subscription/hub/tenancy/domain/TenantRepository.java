package dev.lumjahaj.subscription.hub.tenancy.domain;

import java.util.List;
import java.util.Optional;

public interface TenantRepository {
    Optional<Tenant> findActiveById(String id);
    List<Tenant> findAllActive();
}
