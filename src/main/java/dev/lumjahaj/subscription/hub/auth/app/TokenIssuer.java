package dev.lumjahaj.subscription.hub.auth.app;

import dev.lumjahaj.subscription.hub.config.JwtConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Signs a token with the claims every token shares — issuer, lifetime,
 * subject — plus whatever the caller adds. Tenant and platform tokens
 * differ only in those extra claims, so the part that must be identical
 * for JwtConfig's decoder to accept them lives here once.
 */
@Component
class TokenIssuer {

    record IssuedToken(String token, Instant expiresAt) {
    }

    private final JwtEncoder jwtEncoder;
    private final Duration ttl;

    TokenIssuer(JwtEncoder jwtEncoder, @Value("${auth.jwt.ttl}") Duration ttl) {
        this.jwtEncoder = jwtEncoder;
        this.ttl = ttl;
    }

    IssuedToken issue(String subject, Map<String, Object> claims) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(ttl);

        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(subject);
        claims.forEach(builder::claim);

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, builder.build())).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }
}
