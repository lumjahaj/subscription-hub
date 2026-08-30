package dev.lumjahaj.subscription.hub.auth.api;

import dev.lumjahaj.subscription.hub.auth.api.dto.TokenRequest;
import dev.lumjahaj.subscription.hub.auth.api.dto.TokenResponse;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductResponse;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerCreateRequest;
import dev.lumjahaj.subscription.hub.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the role rules actually refuse someone, using the SUPPORT user
 * V9 seeds.
 *
 * Every other integration test authenticates as an ADMIN, so they only
 * ever exercise the direction that succeeds — none of them would notice
 * if @PreAuthorize were deleted outright. These assert the negative.
 */
class AuthorizationIntegrationTest extends AbstractIntegrationTest {

    private static final String DEV_PASSWORD = "subscriptionhub";

    @Test
    void supportUsersToken_carriesOnlyTheSupportRole() {
        TokenResponse token = supportLogin();

        assertThat(token.roles()).containsExactly("SUPPORT");
        assertThat(token.tenantId()).isEqualTo("acme");
    }

    @Test
    void support_canReadTheCatalog() {
        // Reads carry no @PreAuthorize by design: being authenticated is
        // the whole requirement. This is what makes the 403 below meaningful
        // rather than the user simply being locked out of everything.
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/products", HttpMethod.GET,
                new HttpEntity<>(supportHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void support_cannotWriteToTheCatalog() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/products", HttpMethod.POST,
                new HttpEntity<>(new ProductCreateRequest("denied-" + UUID.randomUUID(), "Denied", null),
                        supportHeaders()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("ACCESS_DENIED");
    }

    @Test
    void support_cannotPerformCommercialWrites() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/customers", HttpMethod.POST,
                new HttpEntity<>(new CustomerCreateRequest(null, "denied-" + UUID.randomUUID() + "@acme.test", "Denied"),
                        supportHeaders()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aDeniedRequest_is403NotAMisreported500() {
        // The specific regression this guards: @PreAuthorize throws
        // AccessDeniedException during dispatch, so ProblemDetailsAdvice's
        // catch-all @ExceptionHandler(Exception.class) would report a
        // forbidden request as 500 INTERNAL_ERROR unless AccessDeniedException
        // is handled ahead of it. A denied endpoint would then look broken
        // rather than protected.
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/products", HttpMethod.POST,
                new HttpEntity<>(new ProductCreateRequest("denied-" + UUID.randomUUID(), "Denied", null),
                        supportHeaders()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).doesNotContain("INTERNAL_ERROR");
        assertThat(response.getHeaders().getContentType())
                .hasToString(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getBody()).contains("requestId");
    }

    @Test
    void a403_isDistinguishedFromA401() {
        // Authenticated-but-forbidden and unauthenticated must not collapse
        // into the same answer: one means "log in", the other "you already
        // did, and it isn't enough".
        HttpEntity<ProductCreateRequest> denied = new HttpEntity<>(
                new ProductCreateRequest("denied-" + UUID.randomUUID(), "Denied", null), supportHeaders());
        HttpEntity<ProductCreateRequest> anonymous = new HttpEntity<>(
                new ProductCreateRequest("anon-" + UUID.randomUUID(), "Anon", null), new HttpHeaders());

        assertThat(restTemplate.exchange("/api/products", HttpMethod.POST, denied, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(restTemplate.exchange("/api/products", HttpMethod.POST, anonymous, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void admin_canDoWhatSupportCannot() {
        // The mirror image, so a 403 above can't be passing for some
        // unrelated reason (a broken payload, say) rather than the role.
        ResponseEntity<ProductResponse> response = restTemplate.exchange(
                "/api/products", HttpMethod.POST,
                new HttpEntity<>(new ProductCreateRequest("allowed-" + UUID.randomUUID(), "Allowed", null),
                        tenantHeaders("acme")),
                ProductResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private HttpHeaders supportHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(supportLogin().token());
        return headers;
    }

    private TokenResponse supportLogin() {
        ResponseEntity<TokenResponse> response = restTemplate.exchange(
                "/api/auth/token", HttpMethod.POST,
                new HttpEntity<>(new TokenRequest("acme", "support@acme.test", DEV_PASSWORD)),
                TokenResponse.class);
        assertThat(response.getStatusCode())
                .as("login for the seeded SUPPORT user")
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }
}
