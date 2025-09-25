package dev.lumjahaj.subscription.hub.tenancy;

import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;

@Repository
public class InMemoryTenantRepository implements TenantRepository {
    private final Map<String, Tenant> tenants = Map.of(
            "acme", new Tenant("acme", "Acme Inc.", true),
            "demo", new Tenant("demo", "Demo Tenant", true)
    );

    @Override
    public Optional<Tenant> findActiveById(String id) {
        Tenant t = tenants.get(id);
        return (t != null && t.active()) ? Optional.of(t) : Optional.empty();
    }
}
