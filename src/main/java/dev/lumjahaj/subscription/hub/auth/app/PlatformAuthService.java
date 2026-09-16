package dev.lumjahaj.subscription.hub.auth.app;

import dev.lumjahaj.subscription.hub.auth.api.dto.PlatformTokenRequest;
import dev.lumjahaj.subscription.hub.auth.api.dto.PlatformTokenResponse;
import dev.lumjahaj.subscription.hub.auth.domain.PlatformRole;
import dev.lumjahaj.subscription.hub.auth.domain.PlatformUserRepository;
import dev.lumjahaj.subscription.hub.auth.infra.jpa.PlatformUserEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;

/**
 * Login for platform operators. The same checks as AuthService — one error
 * for every failure, a bcrypt verification on every path — but a different
 * user directory and a token that names no tenant.
 */
@Service
public class PlatformAuthService {

    private final PlatformUserRepository users;
    private final PasswordVerifier passwordVerifier;
    private final TokenIssuer tokenIssuer;

    public PlatformAuthService(
            PlatformUserRepository users,
            PasswordVerifier passwordVerifier,
            TokenIssuer tokenIssuer
    ) {
        this.users = users;
        this.passwordVerifier = passwordVerifier;
        this.tokenIssuer = tokenIssuer;
    }

    @Transactional(readOnly = true)
    public PlatformTokenResponse issueToken(PlatformTokenRequest request) {
        PlatformUserEntity user = passwordVerifier.verify(
                users.findByEmail(request.email()),
                PlatformUserEntity::getPasswordHash,
                PlatformUserEntity::isEnabled,
                request.password());

        Set<String> roles = Set.of(PlatformRole.PLATFORM_ADMIN);

        // The absence of tenant_id is the point, not an omission. SecurityConfig
        // admits a token to tenant endpoints only if it carries that claim,
        // and to /api/platform/** only if it carries this role — so neither
        // kind of token can stand in for the other.
        TokenIssuer.IssuedToken issued = tokenIssuer.issue(user.getId().toString(), Map.of("roles", roles));

        return new PlatformTokenResponse(issued.token(), "Bearer", issued.expiresAt(), roles);
    }
}
