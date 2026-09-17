package dev.lumjahaj.subscription.hub.tenancy.infra.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TenantJpaRepository extends JpaRepository<TenantEntity, String> {
    Optional<TenantEntity> findByIdAndActiveTrue(String id);
    List<TenantEntity> findByActiveTrue();

    /**
     * Flips the flag only if it differs, in one statement, and reports whether
     * it did. JPQL rather than native: tenant is not TenantScoped, so there is
     * no @TenantId predicate to lose either way.
     *
     * A bulk update skips entity callbacks, so updatedAt is set here rather than
     * by TenantEntity's @PreUpdate. clearAutomatically because the caller has
     * usually just loaded this tenant, and that copy would otherwise still say
     * the old value.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update TenantEntity t
               set t.active = :active, t.updatedAt = :now
             where t.id = :id and t.active <> :active
            """)
    int updateActiveIfDifferent(@Param("id") String id, @Param("active") boolean active, @Param("now") Instant now);
}
