package dev.lumjahaj.subscription.hub.tenancy.infra.jpa;

import dev.lumjahaj.subscription.hub.tenancy.domain.Tenant;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantRepository;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class TenantRepositoryImpl implements TenantRepository {

    private final TenantJpaRepository jpa;
    private final EntityManager entityManager;

    public TenantRepositoryImpl(TenantJpaRepository jpa, EntityManager entityManager) {
        this.jpa = jpa;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<Tenant> findActiveById(String id) {
        return jpa.findByIdAndActiveTrue(id).map(TenantRepositoryImpl::toDomain);
    }

    @Override
    public List<Tenant> findAllActive() {
        return jpa.findByActiveTrue().stream().map(TenantRepositoryImpl::toDomain).toList();
    }

    @Override
    public Optional<Tenant> findById(String id) {
        return jpa.findById(id).map(TenantRepositoryImpl::toDomain);
    }

    @Override
    public Page<Tenant> findAll(Pageable pageable) {
        return jpa.findAll(pageable).map(TenantRepositoryImpl::toDomain);
    }

    /**
     * persist, not jpa.save. SimpleJpaRepository.save decides between
     * persist and merge by asking whether the entity is new, which for an
     * entity with an assigned id means "is the id null" — never, here. So
     * save would merge: load the existing row and copy this one over it.
     * The flush forces the INSERT now, so a duplicate id surfaces as a
     * primary-key violation inside this call, where the @Repository
     * exception translation turns it into a DataIntegrityViolationException,
     * rather than at some later commit.
     *
     * This is the first repository adapter that needed more than
     * delegation — the seam CLAUDE.md §3 says the trio exists to provide.
     */
    @Override
    public Tenant create(Tenant tenant) {
        TenantEntity entity = new TenantEntity();
        entity.setId(tenant.id());
        entity.setName(tenant.name());
        entity.setActive(tenant.active());
        entityManager.persist(entity);
        entityManager.flush();
        return toDomain(entity);
    }

    @Override
    public boolean setActive(String id, boolean active) {
        return jpa.updateActiveIfDifferent(id, active, Instant.now()) == 1;
    }

    private static Tenant toDomain(TenantEntity entity) {
        return new Tenant(entity.getId(), entity.getName(), entity.isActive());
    }
}
