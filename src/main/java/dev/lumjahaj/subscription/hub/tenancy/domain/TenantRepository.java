package dev.lumjahaj.subscription.hub.tenancy.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface TenantRepository {
    Optional<Tenant> findActiveById(String id);
    List<Tenant> findAllActive();

    /** Active or not — the platform API has to be able to see a deactivated tenant to reactivate it. */
    Optional<Tenant> findById(String id);

    Page<Tenant> findAll(Pageable pageable);

    /**
     * Inserts a new tenant, and never updates an existing one.
     *
     * Named create rather than save on purpose: a tenant's id is an assigned
     * slug, not a generated value, and for such an entity a save() would
     * merge — silently overwriting an existing tenant's name instead of
     * failing when two requests race past the existence check. A duplicate
     * id here fails on the primary key instead.
     */
    Tenant create(Tenant tenant);

    /** Returns the updated tenant, or empty if no tenant has that id. */
    Optional<Tenant> setActive(String id, boolean active);
}
