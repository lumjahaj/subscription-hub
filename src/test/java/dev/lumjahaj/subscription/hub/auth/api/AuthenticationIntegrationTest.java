package dev.lumjahaj.subscription.hub.auth.api;

import dev.lumjahaj.subscription.hub.auth.api.dto.TokenRequest;
import dev.lumjahaj.subscription.hub.auth.api.dto.TokenResponse;
import dev.lumjahaj.subscription.hub.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the login endpoint against the users V8 seeds. The token's
 * contents are decoded and asserted directly rather than only being used
 * — the tenant_id claim is what every later request derives its tenant
 * from, so if it were wrong or missing the failure would otherwise
 * surface far away from the cause.
 */
class AuthenticationIntegrationTest extends AbstractIntegrationTest {

    private static final String DEV_PASSWORD = "subscriptionhub";

    @Autowired
    private JwtDecoder jwtDecoder;

    @Test
    void login_withSeededAdminCredentials_returnsAToken() {
        ResponseEntity<TokenResponse> response = login("acme", "admin@acme.test", DEV_PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        TokenResponse body = response.getBody();
        assertThat(body.token()).isNotBlank();
        assertThat(body.tokenType()).isEqualTo("Bearer");
        assertThat(body.tenantId()).isEqualTo("acme");
        assertThat(body.roles()).containsExactly("ADMIN");
        assertThat(body.expiresAt()).isAfter(Instant.now());
    }

    @Test
    void issuedToken_carriesTheTenantAndRolesAsVerifiableClaims() {
        String token = login("acme", "admin@acme.test", DEV_PASSWORD).getBody().token();

        // Decoding through the application's own JwtDecoder also proves the
        // signature and issuer validate - a token this decoder rejects is
        // one the resource server would reject too.
        Jwt jwt = jwtDecoder.decode(token);

        assertThat(jwt.getClaimAsString("tenant_id")).isEqualTo("acme");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ADMIN");
        assertThat(jwt.getSubject()).isNotBlank();
        // Read as a raw claim, not via jwt.getIssuer(), which coerces to a
        // URL and throws for anything else. RFC 7519 allows iss to be a
        // plain StringOrURI, and this issuer is deliberately an opaque
        // identifier rather than a fake https host implying an
        // OIDC-discoverable endpoint that doesn't exist. It becomes a real
        // URL if an external IdP ever replaces this.
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("subscription-hub");
        assertThat(jwt.getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void login_forADifferentTenant_returnsThatTenantsClaim() {
        String token = login("demo", "admin@demo.test", DEV_PASSWORD).getBody().token();

        assertThat(jwtDecoder.decode(token).getClaimAsString("tenant_id")).isEqualTo("demo");
    }

    @Test
    void login_withTheWrongPassword_isUnauthorized() {
        ResponseEntity<String> response =
                login("acme", "admin@acme.test", "not-the-password", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("INVALID_CREDENTIALS");
    }

    @Test
    void login_withAnUnknownEmail_isUnauthorizedWithTheSameCode() {
        // Deliberately indistinguishable from a wrong password: a different
        // code here would let anyone enumerate which emails are registered.
        ResponseEntity<String> response =
                login("acme", "nobody@acme.test", DEV_PASSWORD, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("INVALID_CREDENTIALS");
    }

    @Test
    void login_withAnUnknownTenant_isUnauthorizedWithTheSameCode() {
        ResponseEntity<String> response =
                login("no-such-tenant", "admin@acme.test", DEV_PASSWORD, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("INVALID_CREDENTIALS");
    }

    @Test
    void login_withAValidUserButTheWrongTenant_isUnauthorized() {
        // acme's admin must not authenticate against demo's user directory,
        // even though the email exists somewhere and the password is right.
        ResponseEntity<String> response =
                login("demo", "admin@acme.test", DEV_PASSWORD, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("INVALID_CREDENTIALS");
    }

    @Test
    void login_withoutRequiredFields_isABadRequest() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/auth/token", HttpMethod.POST,
                new HttpEntity<>(new TokenRequest(null, null, null)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("VALIDATION_ERROR");
    }

    @Test
    void aTamperedToken_isRejectedByTheDecoder() {
        String token = login("acme", "admin@acme.test", DEV_PASSWORD).getBody().token();
        // Flip a character in the payload so the signature no longer matches.
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2)
                + (parts[1].endsWith("A") ? "B" : "A") + "." + parts[2];

        assertThat(tampered).isNotEqualTo(token);
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> jwtDecoder.decode(tampered))
                .isInstanceOf(Exception.class);
    }

    private ResponseEntity<TokenResponse> login(String tenantId, String email, String password) {
        return login(tenantId, email, password, TokenResponse.class);
    }

    private <T> ResponseEntity<T> login(String tenantId, String email, String password, Class<T> responseType) {
        return restTemplate.exchange(
                "/api/auth/token", HttpMethod.POST,
                new HttpEntity<>(new TokenRequest(tenantId, email, password)), responseType);
    }
}
