package dev.lumjahaj.subscription.hub.auth.app;

import dev.lumjahaj.subscription.hub.auth.api.dto.TokenRequest;
import dev.lumjahaj.subscription.hub.auth.api.dto.TokenResponse;
import dev.lumjahaj.subscription.hub.auth.domain.AppUserRepository;
import dev.lumjahaj.subscription.hub.auth.domain.Role;
import dev.lumjahaj.subscription.hub.auth.infra.jpa.AppUserEntity;
import dev.lumjahaj.subscription.hub.config.JwtConfig;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AuthService {

    /**
     * A valid bcrypt hash of a value nothing will ever submit. Used to
     * spend the same ~100ms of hashing when no user was found as when one
     * was, so response time doesn't reveal which emails exist — the same
     * reason every failure below throws the identical exception.
     */
    private static final String DUMMY_HASH =
            "$2a$10$weWhaGYAMz9Fzc9M.xikWefRTaxBFywEYnMyYYZYz/LTSnaeP8gci";

    private final AppUserRepository users;
    private final TenantRepository tenants;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final Duration ttl;

    public AuthService(
            AppUserRepository users,
            TenantRepository tenants,
            PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder,
            @Value("${auth.jwt.ttl}") Duration ttl
    ) {
        this.users = users;
        this.tenants = tenants;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.ttl = ttl;
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

        // Always hash, even on the paths that already cannot succeed, so
        // response time doesn't reveal which tenants and emails exist.
        String hashToCheck = found.map(AppUserEntity::getPasswordHash).orElse(DUMMY_HASH);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hashToCheck);

        AppUserEntity user = found
                .filter(u -> passwordMatches && u.isEnabled())
                .orElseThrow(InvalidCredentialsException::new);

        return issueFor(user);
    }

    private TokenResponse issueFor(AppUserEntity user) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(ttl);
        Set<String> roles = user.getRoles().stream().map(Role::name).collect(Collectors.toSet());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(user.getId().toString())
                // Snake case because these are wire-format JWT claims, not
                // Java properties - TenantResolverFilter reads tenant_id
                // back out on every subsequent request.
                .claim("tenant_id", user.getTenantId())
                .claim("roles", roles)
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return new TokenResponse(token, "Bearer", expiresAt, user.getTenantId(), roles);
    }
}
