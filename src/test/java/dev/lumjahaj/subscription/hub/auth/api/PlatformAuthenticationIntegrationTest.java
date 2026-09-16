package dev.lumjahaj.subscription.hub.auth.api;

import dev.lumjahaj.subscription.hub.auth.api.dto.PlatformTokenRequest;
import dev.lumjahaj.subscription.hub.auth.api.dto.PlatformTokenResponse;
import dev.lumjahaj.subscription.hub.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The platform login, and — more importantly — that platform and tenant
 * tokens cannot stand in for each other, in either direction.
 *
 * The tenant-endpoint probes use GET /api/products, a read with no
 * {@code @PreAuthorize}: a 403 there can only come from the URL-level
 * tenant_id rule, not from a role check that would have refused anyway.
 */
class PlatformAuthenticationIntegrationTest extends AbstractIntegrationTest {

    private static final String PLATFORM_EMAIL = "platform@subscriptionhub.test";
    private static final String DEV_PASSWORD = "subscriptionhub";

    @Autowired
    private JwtDecoder jwtDecoder;

    @Test
    void login_withSeededPlatformAdmin_returnsATokenWithNoTenant() {
        ResponseEntity<PlatformTokenResponse> response = login(PLATFORM_EMAIL, DEV_PASSWORD, PlatformTokenResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().roles()).containsExactly("PLATFORM_ADMIN");

        Jwt jwt = jwtDecoder.decode(response.getBody().token());
        assertThat(jwt.hasClaim("tenant_id")).isFalse();
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("PLATFORM_ADMIN");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("subscription-hub");
    }

    @Test
    void login_withTheWrongPassword_isUnauthorized() {
        ResponseEntity<String> response = login(PLATFORM_EMAIL, "not-the-password", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("INVALID_CREDENTIALS");
    }

    @Test
    void login_withAnUnknownEmail_isUnauthorizedWithTheSameCode() {
        ResponseEntity<String> response = login("nobody@subscriptionhub.test", DEV_PASSWORD, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("INVALID_CREDENTIALS");
    }

    @Test
    void login_withATenantAdminsCredentials_isUnauthorized() {
        // Same password, a real account - but in app_user, not platform_user.
        // A tenant administrator is not a platform administrator.
        ResponseEntity<String> response = login("admin@acme.test", DEV_PASSWORD, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("INVALID_CREDENTIALS");
    }

    @Test
    void login_withoutRequiredFields_isABadRequest() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/platform/auth/token", HttpMethod.POST,
                new HttpEntity<>(new PlatformTokenRequest(null, null)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("VALIDATION_ERROR");
    }

    @Test
    void aPlatformToken_isRefusedOnTenantEndpoints() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/products", HttpMethod.GET, new HttpEntity<>(platformHeaders()), String.class);

        // 403, not 400 TENANT_MISSING: the caller is authenticated, just not
        // as anyone a tenant endpoint serves.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("ACCESS_DENIED");
    }

    @Test
    void aTenantAdminToken_isRefusedOnPlatformEndpoints() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/platform/tenants", HttpMethod.GET, new HttpEntity<>(tenantHeaders("acme")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("ACCESS_DENIED");
    }

    @Test
    void platformEndpoints_withoutAToken_areUnauthorized() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/platform/tenants", HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("UNAUTHENTICATED");
    }

    @Test
    void tenantEndpoints_withoutAToken_areStillUnauthorizedNotForbidden() {
        // The tenant rule replaced authenticated(); an anonymous caller must
        // still be told to log in, not that they are forbidden.
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/products", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aTenantToken_stillWorksOnTenantEndpoints() {
        // The mirror image, so the 403s above can't be passing because the
        // tenant rule refuses everyone.
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/products", HttpMethod.GET, new HttpEntity<>(tenantHeaders("acme")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private <T> ResponseEntity<T> login(String email, String password, Class<T> responseType) {
        return restTemplate.exchange(
                "/api/platform/auth/token", HttpMethod.POST,
                new HttpEntity<>(new PlatformTokenRequest(email, password)), responseType);
    }
}
