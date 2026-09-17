package dev.lumjahaj.subscription.hub.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import dev.lumjahaj.subscription.hub.auth.api.dto.TokenRequest;
import dev.lumjahaj.subscription.hub.auth.api.dto.TokenResponse;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductCreateRequest;
import dev.lumjahaj.subscription.hub.platform.api.dto.TenantCreateRequest;
import dev.lumjahaj.subscription.hub.platform.api.dto.TenantProvisionedResponse;
import dev.lumjahaj.subscription.hub.platform.api.dto.TenantResponse;
import dev.lumjahaj.subscription.hub.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provisioning end to end: a tenant that did not exist becomes one a real
 * administrator can log in to and use, then stops working when deactivated.
 *
 * Every test provisions its own uniquely named tenant, since the database is
 * shared by every integration test class in the run.
 */
class PlatformTenantProvisioningIntegrationTest extends AbstractIntegrationTest {

    @Test
    void provisioning_createsATenantItsAdminCanLogInToAndUse() {
        String id = newTenantId();

        ResponseEntity<TenantProvisionedResponse> created = provision(id);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation()).hasToString("/api/platform/tenants/" + id);
        TenantProvisionedResponse body = created.getBody();
        assertThat(body.tenant()).isEqualTo(new TenantResponse(id, "Globex " + id, true));
        assertThat(body.initialPassword()).isNotBlank();

        // The real proof: the returned password works, and the token it buys
        // is scoped to the new tenant and can write to it.
        String token = loginAsAdmin(id, body.adminEmail(), body.initialPassword()).token();
        ResponseEntity<String> product = restTemplate.exchange(
                "/api/products", HttpMethod.POST,
                new HttpEntity<>(new ProductCreateRequest("starter", "Starter", null), bearer(token)),
                String.class);
        assertThat(product.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void provisionedTenant_isIsolatedFromExistingTenants() {
        String id = newTenantId();
        TenantProvisionedResponse body = provision(id).getBody();
        String token = loginAsAdmin(id, body.adminEmail(), body.initialPassword()).token();

        // acme has seeded and test-created products; a brand-new tenant sees none.
        ResponseEntity<String> products = restTemplate.exchange(
                "/api/products", HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class);

        assertThat(products.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(products.getBody()).contains("\"totalElements\":0");
    }

    @Test
    void theInitialPassword_isNeverReturnedAgain() {
        String id = newTenantId();
        String password = provision(id).getBody().initialPassword();

        ResponseEntity<String> fetched = restTemplate.exchange(
                "/api/platform/tenants/" + id, HttpMethod.GET, new HttpEntity<>(platformHeaders()), String.class);

        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).doesNotContain(password).doesNotContain("initialPassword");
    }

    @Test
    void aDuplicateId_isAConflict() {
        String id = newTenantId();
        provision(id);

        ResponseEntity<String> again = restTemplate.exchange(
                "/api/platform/tenants", HttpMethod.POST,
                new HttpEntity<>(request(id), platformHeaders()), String.class);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).contains("TENANT_ALREADY_EXISTS");
    }

    @Test
    void anExistingTenant_isNeverOverwritten() {
        // acme already exists. A create that merged instead of inserting would
        // quietly rename it; it must be refused and leave acme untouched.
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/platform/tenants", HttpMethod.POST,
                new HttpEntity<>(new TenantCreateRequest("acme", "Hijacked", "evil@example.com"), platformHeaders()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<TenantResponse> acme = restTemplate.exchange(
                "/api/platform/tenants/acme", HttpMethod.GET, new HttpEntity<>(platformHeaders()), TenantResponse.class);
        assertThat(acme.getBody().name()).isEqualTo("Acme Inc.");
    }

    @Test
    void concurrentCreatesOfTheSameId_produceExactlyOneTenant() throws Exception {
        // Both requests can pass the existence check; tenant_pkey is what
        // decides. The losers must get the same 409, never a 500 and never an
        // overwrite - mirroring the invoice and payment concurrency proofs.
        String id = newTenantId();
        HttpHeaders headers = platformHeaders();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<HttpStatus>> calls = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                calls.add(() -> HttpStatus.valueOf(restTemplate.exchange(
                        "/api/platform/tenants", HttpMethod.POST,
                        new HttpEntity<>(request(id), headers), String.class).getStatusCode().value()));
            }
            List<HttpStatus> statuses = new ArrayList<>();
            for (Future<HttpStatus> result : pool.invokeAll(calls)) {
                statuses.add(result.get());
            }

            assertThat(statuses).filteredOn(HttpStatus.CREATED::equals).hasSize(1);
            assertThat(statuses).filteredOn(HttpStatus.CONFLICT::equals).hasSize(threads - 1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anInvalidSlug_isABadRequest() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/platform/tenants", HttpMethod.POST,
                new HttpEntity<>(request("Not_A_Slug"), platformHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("VALIDATION_ERROR");
    }

    @Test
    void anUnknownTenant_isNotFound() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/platform/tenants/no-such-tenant", HttpMethod.GET,
                new HttpEntity<>(platformHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("TENANT_NOT_FOUND");
    }

    @Test
    void list_includesExistingAndProvisionedTenants() {
        String id = newTenantId();
        provision(id);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/platform/tenants?size=1000", HttpMethod.GET,
                new HttpEntity<>(platformHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"id\":\"acme\"").contains("\"id\":\"" + id + "\"");
    }

    @Test
    void deactivation_stopsTokensAlreadyIssued_andActivationRestoresThem() {
        String id = newTenantId();
        TenantProvisionedResponse body = provision(id).getBody();
        HttpHeaders tenantAdmin = bearer(loginAsAdmin(id, body.adminEmail(), body.initialPassword()).token());

        ResponseEntity<TenantResponse> deactivated = restTemplate.exchange(
                "/api/platform/tenants/" + id + "/deactivate", HttpMethod.POST,
                new HttpEntity<>(platformHeaders()), TenantResponse.class);
        assertThat(deactivated.getBody().active()).isFalse();

        // Same token as before, still unexpired - refused because
        // TenantResolverFilter re-checks the tenant on every request.
        ResponseEntity<String> whileInactive = restTemplate.exchange(
                "/api/products", HttpMethod.GET, new HttpEntity<>(tenantAdmin), String.class);
        assertThat(whileInactive.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(whileInactive.getBody()).contains("TENANT_UNKNOWN");

        // Deactivating twice is a no-op, not an error.
        assertThat(restTemplate.exchange(
                "/api/platform/tenants/" + id + "/deactivate", HttpMethod.POST,
                new HttpEntity<>(platformHeaders()), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        restTemplate.exchange(
                "/api/platform/tenants/" + id + "/activate", HttpMethod.POST,
                new HttpEntity<>(platformHeaders()), TenantResponse.class);

        ResponseEntity<String> afterActivation = restTemplate.exchange(
                "/api/products", HttpMethod.GET, new HttpEntity<>(tenantAdmin), String.class);
        assertThat(afterActivation.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Two deactivate calls but one change, so one event.
        JsonNode history = platformAuditHistory(id);
        assertThat(history).extracting(event -> event.get("type").asText())
                .containsExactly("TENANT_ACTIVATED", "TENANT_DEACTIVATED", "TENANT_PROVISIONED");
        assertThat(history).allSatisfy(event -> {
            assertThat(event.get("actorType").asText()).isEqualTo("PLATFORM_ADMIN");
            assertThat(event.get("entityId").asText()).isEqualTo(id);
        });
        // The tenant sees its own history, including what the platform did to it.
        assertThat(restTemplate.exchange(
                "/api/audit-events", HttpMethod.GET, new HttpEntity<>(tenantAdmin), JsonNode.class)
                .getBody().get("content")).extracting(event -> event.get("type").asText())
                .contains("TENANT_PROVISIONED", "TENANT_DEACTIVATED", "TENANT_ACTIVATED");
    }

    @Test
    void auditEventsOfAnUnknownTenant_isNotFound() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/platform/tenants/" + newTenantId() + "/audit-events", HttpMethod.GET,
                new HttpEntity<>(platformHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aTenantAdmin_cannotProvisionTenants() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/platform/tenants", HttpMethod.POST,
                new HttpEntity<>(request(newTenantId()), tenantHeaders("acme")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private ResponseEntity<TenantProvisionedResponse> provision(String id) {
        ResponseEntity<TenantProvisionedResponse> response = restTemplate.exchange(
                "/api/platform/tenants", HttpMethod.POST,
                new HttpEntity<>(request(id), platformHeaders()), TenantProvisionedResponse.class);
        assertThat(response.getStatusCode()).as("provisioning tenant '%s'", id).isEqualTo(HttpStatus.CREATED);
        return response;
    }

    private JsonNode platformAuditHistory(String tenantId) {
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/platform/tenants/" + tenantId + "/audit-events", HttpMethod.GET,
                new HttpEntity<>(platformHeaders()), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().get("content");
    }

    private TokenResponse loginAsAdmin(String tenantId, String email, String password) {
        ResponseEntity<TokenResponse> response = restTemplate.exchange(
                "/api/auth/token", HttpMethod.POST,
                new HttpEntity<>(new TokenRequest(tenantId, email, password)), TokenResponse.class);
        assertThat(response.getStatusCode()).as("login for provisioned tenant '%s'", tenantId).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static TenantCreateRequest request(String id) {
        return new TenantCreateRequest(id, "Globex " + id, "admin@" + id + ".test");
    }

    private static String newTenantId() {
        return "t-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
