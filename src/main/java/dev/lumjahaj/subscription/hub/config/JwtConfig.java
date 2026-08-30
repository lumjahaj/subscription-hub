package dev.lumjahaj.subscription.hub.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Signing and verification for self-issued JWTs.
 *
 * Symmetric (HS256) rather than RSA because the issuer and the validator
 * are the same process — asymmetric keys exist so a *separate* identity
 * provider can publish a public key that others verify against, which
 * buys nothing here. Swapping to Keycloak later means deleting the auth
 * module and setting
 * spring.security.oauth2.resourceserver.jwt.issuer-uri: the verification
 * side below is already the standard resource-server machinery, so the
 * change is configuration, not a rewrite — the same "code against the
 * standard, keep the provider a config value" shape as MinIO and the S3
 * SDK.
 */
@Configuration
public class JwtConfig {

    public static final String ISSUER = "subscription-hub";

    /** HS256 needs a key of at least 256 bits; anything shorter is rejected outright. */
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKeySpec secretKey;

    public JwtConfig(@Value("${auth.jwt.secret}") String secret) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            // Fail at startup rather than at the first login. Nimbus would
            // otherwise throw a much less obvious error the first time
            // someone tries to sign, in an environment where nobody is
            // watching the logs.
            throw new IllegalStateException(
                    "auth.jwt.secret must be at least " + MIN_SECRET_BYTES
                            + " bytes for HS256, but was " + keyBytes.length);
        }
        this.secretKey = new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey));
    }

    /**
     * Validates signature and expiry (Spring's default validators) and
     * additionally that the issuer is ours — so a token signed with the
     * same secret by some other service can't be replayed here.
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                new JwtIssuerValidator(ISSUER)));
        return decoder;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
