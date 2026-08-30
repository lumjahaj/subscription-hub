package dev.lumjahaj.subscription.hub.auth.infra.jpa;

import dev.lumjahaj.subscription.hub.auth.domain.Role;
import jakarta.persistence.*;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Who logs in to the API — distinct from CustomerEntity, which is who a
 * tenant bills. Table is app_user rather than user because "user" is
 * reserved in Postgres (see V8).
 *
 * <p><b>The one tenant-owned entity that deliberately does NOT extend
 * TenantScoped.</b> Everything else does, which adds Hibernate's
 * automatic {@code tenant_id = <current tenant>} predicate via
 * {@code @TenantId}. That predicate is resolved from TenantContext when
 * the Hibernate Session opens — and with {@code spring.jpa.open-in-view}
 * enabled, the session opens at the *start of the request*, before any
 * controller runs.
 *
 * <p>For every other entity that is fine: the tenant is already in
 * context, put there by TenantResolverFilter. For this one it cannot be,
 * because reading this table is what establishes which tenant the caller
 * belongs to. Extending TenantScoped produced a genuinely
 * self-contradicting query —
 * {@code where tenant_id = '__no_tenant__' and tenant_id = 'acme'} — so
 * every login failed with correct credentials. A chicken-and-egg, not a
 * bug to be patched around.
 *
 * <p>Isolation is not weakened: AppUserRepository exposes only
 * {@code findByTenantIdAndEmail}, so the tenant predicate is explicit and
 * mandatory, which CLAUDE.md §4 already requires as the primary mechanism
 * ({@code @TenantId} is documented there as a safety net on top, not a
 * replacement). The issued token also takes its tenant from the row that
 * was found, never from the request, so a wrong lookup could not mint a
 * token for a tenant the password does not belong to.
 */
@Entity
@Table(
        name = "app_user",
        uniqueConstraints = @UniqueConstraint(name = "uk_app_user_tenant_email", columnNames = {"tenant_id", "email"})
)
public class AppUserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // A plain column, not TenantScoped's @TenantId field — see above.
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "email", nullable = false)
    private String email;

    // bcrypt. Never logged and never returned in a response; AuthService
    // reads it only to hand to PasswordEncoder.matches.
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    // created_at / updated_at exist in the table (with DEFAULT now()) but
    // are deliberately unmapped: nothing in the application writes to
    // app_user — users are seeded — so there is no lifecycle for Java to
    // manage, and ddl-auto: validate does not require every column to be
    // mapped. Map them if a user-management endpoint ever lands.

    // The codebase's first @ElementCollection. Roles are a value set owned
    // entirely by this user — no identity of their own, never queried
    // independently — so a full entity plus repository trio would be
    // ceremony for a four-value enum, while a delimited varchar column
    // would give up the database's ability to constrain them at all
    // (chk_app_user_role_value in V8 does exactly that).
    //
    // EAGER because every load of a user is immediately followed by
    // reading its roles to mint a token; there is no path that loads a
    // user and doesn't want them.
    //
    // Plain @Enumerated(EnumType.STRING): role is varchar(32), NOT a
    // native Postgres enum, so the NAMED_ENUM combo InvoiceEntity.status
    // needs would bind a nonexistent type here. Same trap, same answer as
    // InvoiceLineEntity.kind.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "app_user_role", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role", length = 32, nullable = false)
    @Enumerated(EnumType.STRING)
    private Set<Role> roles = new HashSet<>();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Set<Role> getRoles() { return roles; }
    public void setRoles(Set<Role> roles) { this.roles = roles; }
}
