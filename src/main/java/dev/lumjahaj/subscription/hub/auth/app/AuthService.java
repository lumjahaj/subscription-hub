package dev.lumjahaj.subscription.hub.auth.app;

import dev.lumjahaj.subscription.hub.auth.api.dto.TokenRequest;
import dev.lumjahaj.subscription.hub.auth.api.dto.TokenResponse;
import dev.lumjahaj.subscription.hub.auth.domain.AppUserRepository;
import dev.lumjahaj.subscription.hub.auth.domain.Role;
import dev.lumjahaj.subscription.hub.auth.infra.jpa.AppUserEntity;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AuthService {

    private final AppUserRepository users;
    private final TenantRepository tenants;
    private final PasswordVerifier passwordVerifier;
    private final TokenIssuer tokenIssuer;

    public AuthService(
            AppUserRepository users,
            TenantRepository tenants,
            PasswordVerifier passwordVerifier,
            TokenIssuer tokenIssuer
    ) {
        this.users = users;
        this.tenants = tenants;
        this.passwordVerifier = passwordVerifier;
        this.tokenIssuer = tokenIssuer;
    }

    /**
     * The one endpoint that accepts a tenant from the caller, because it
     * is the one that runs before any token exists.
     *
     * The tenant is only ever used to *scope the lookup*. Everything the
     * token then asserts is read back off the row that was found — so a
     * caller naming a tenant they have no account in gets nothing, and
     * the claim can never say a tenant the password wasn't proven
     * against. That is why accepting a tenant here doesn't reopen the
     * hole authentication closes.
     *
     * The scoping is explicit rather than via Hibernate's @TenantId,
     * which cannot apply to this table — see AppUserEntity.
     */
    @Transactional(readOnly = true)
    public TokenResponse issueToken(TokenRequest request) {
        Optional<AppUserEntity> found = tenants.findActiveById(request.tenantId()).isPresent()
                ? users.findByTenantIdAndEmail(request.tenantId(), request.email())
                : Optional.empty();

        AppUserEntity user = passwordVerifier.verify(
                "tenant", found, AppUserEntity::getPasswordHash, AppUserEntity::isEnabled, request.password());

        return issueFor(user);
    }

    private TokenResponse issueFor(AppUserEntity user) {
        Set<String> roles = user.getRoles().stream().map(Role::name).collect(Collectors.toSet());

        TokenIssuer.IssuedToken issued = tokenIssuer.issue(user.getId().toString(), Map.of(
                // Snake case because these are wire-format JWT claims, not
                // Java properties - TenantResolverFilter reads tenant_id
                // back out on every subsequent request.
                "tenant_id", user.getTenantId(),
                "roles", roles));

        return new TokenResponse(issued.token(), "Bearer", issued.expiresAt(), user.getTenantId(), roles);
    }
}
