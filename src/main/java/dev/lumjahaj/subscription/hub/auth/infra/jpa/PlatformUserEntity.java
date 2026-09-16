package dev.lumjahaj.subscription.hub.auth.infra.jpa;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Who operates the platform itself, as opposed to AppUserEntity, which is
 * who logs in to one tenant. See V15 for why this is a separate table
 * rather than an app_user with no tenant.
 *
 * <p>Not TenantScoped, and correctly so rather than as a workaround: this
 * row belongs to no tenant. That also means Hibernate's {@code @TenantId}
 * predicate never applies, so the empty TenantContext of a platform request
 * does not pin its queries to the {@code __no_tenant__} sentinel.
 */
@Entity
@Table(
        name = "platform_user",
        uniqueConstraints = @UniqueConstraint(name = "uk_platform_user_email", columnNames = "email")
)
public class PlatformUserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "email", nullable = false)
    private String email;

    // bcrypt. Never logged, never returned.
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    // Mapped here, unlike on AppUserEntity: PlatformAdminBootstrap writes
    // this table from Java, so there is a lifecycle to manage.
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
