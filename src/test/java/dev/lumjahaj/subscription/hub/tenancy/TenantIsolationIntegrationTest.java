package dev.lumjahaj.subscription.hub.tenancy;

import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductResponse;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanResponse;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerCreateRequest;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerResponse;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionCreateRequest;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionResponse;
import dev.lumjahaj.subscription.hub.subscription.domain.SubscriptionStatus;
import dev.lumjahaj.subscription.hub.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the tenant isolation claim CLAUDE.md flags as unverified: that
 * Hibernate's @TenantId (via TenantIdentifierResolver, resolved from
 * TenantContext, populated by TenantResolverFilter) actually blocks a
 * crafted cross-tenant lookup through a real HTTP request - not just that
 * the architecture is supposed to.
 *
 * Uses the tenants Flyway seeds in V1 (acme, demo) rather than creating
 * its own, since both are already active.
 */
class TenantIsolationIntegrationTest extends AbstractIntegrationTest {

    @Test
    void productCreatedUnderOneTenantIsInvisibleToAnother() {
        String code = "iso-" + UUID.randomUUID();
        var createRequest = new ProductCreateRequest(code, "Isolation Product", null);

        ResponseEntity<ProductResponse> created = restTemplate.exchange(
                "/api/products", HttpMethod.POST,
                new HttpEntity<>(createRequest, tenantHeaders("acme")), ProductResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> listAsDemo = restTemplate.exchange(
                "/api/products?size=500", HttpMethod.GET,
                new HttpEntity<>(tenantHeaders("demo")), String.class);
        assertThat(listAsDemo.getBody()).doesNotContain(code);

        ResponseEntity<String> listAsAcme = restTemplate.exchange(
                "/api/products?size=500", HttpMethod.GET,
                new HttpEntity<>(tenantHeaders("acme")), String.class);
        assertThat(listAsAcme.getBody()).contains(code);
    }

    @Test
    void sameProductCodeUnderTwoTenantsBothSucceed_provingTenantScopedNotGlobalUniqueness() {
        String sharedCode = "shared-" + UUID.randomUUID();
        var request = new ProductCreateRequest(sharedCode, "Shared Code Product", null);

        ResponseEntity<ProductResponse> acmeResponse = restTemplate.exchange(
                "/api/products", HttpMethod.POST,
                new HttpEntity<>(request, tenantHeaders("acme")), ProductResponse.class);
        ResponseEntity<ProductResponse> demoResponse = restTemplate.exchange(
                "/api/products", HttpMethod.POST,
                new HttpEntity<>(request, tenantHeaders("demo")), ProductResponse.class);

        assertThat(acmeResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(demoResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(acmeResponse.getBody().id()).isNotEqualTo(demoResponse.getBody().id());
    }

    @Test
    void subscriptionCreatedUnderOneTenantCannotBeCanceledByAnother() {
        UUID subscriptionId = createActiveMonthlySubscription("acme");

        ResponseEntity<String> crossTenantCancel = restTemplate.exchange(
                "/api/subscriptions/" + subscriptionId + "/cancel", HttpMethod.POST,
                new HttpEntity<>(tenantHeaders("demo")), String.class);
        assertThat(crossTenantCancel.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<SubscriptionResponse> ownTenantCancel = restTemplate.exchange(
                "/api/subscriptions/" + subscriptionId + "/cancel", HttpMethod.POST,
                new HttpEntity<>(tenantHeaders("acme")), SubscriptionResponse.class);
        assertThat(ownTenantCancel.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ownTenantCancel.getBody().status()).isEqualTo(SubscriptionStatus.CANCELED);
    }

    @Test
    void subscriptionListByCustomerIdDoesNotLeakAcrossTenants() {
        UUID customerId = createCustomer("acme");
        createActiveMonthlySubscriptionFor("acme", customerId);

        ResponseEntity<PageResponse> listAsDemo = restTemplate.exchange(
                "/api/subscriptions?customerId=" + customerId, HttpMethod.GET,
                new HttpEntity<>(tenantHeaders("demo")), PageResponse.class);

        assertThat(listAsDemo.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listAsDemo.getBody().content()).isEmpty();
    }

    private record PageResponse(java.util.List<Object> content) {
    }

    private UUID createActiveMonthlySubscription(String tenantId) {
        UUID customerId = createCustomer(tenantId);
        return createActiveMonthlySubscriptionFor(tenantId, customerId);
    }

    private UUID createActiveMonthlySubscriptionFor(String tenantId, UUID customerId) {
        String suffix = UUID.randomUUID().toString();

        var productRequest = new ProductCreateRequest("prod-" + suffix, "Isolation Test Product", null);
        ResponseEntity<ProductResponse> productResponse = restTemplate.exchange(
                "/api/products", HttpMethod.POST,
                new HttpEntity<>(productRequest, tenantHeaders(tenantId)), ProductResponse.class);
        assertThat(productResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var planRequest = new PlanCreateRequest(
                productRequest.code(), "plan-" + suffix, "Isolation Test Plan",
                "MONTH", 1, 1999L, "USD", 0);
        ResponseEntity<PlanResponse> planResponse = restTemplate.exchange(
                "/api/plans", HttpMethod.POST,
                new HttpEntity<>(planRequest, tenantHeaders(tenantId)), PlanResponse.class);
        assertThat(planResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var subscriptionRequest = new SubscriptionCreateRequest(customerId, planResponse.getBody().code());
        ResponseEntity<SubscriptionResponse> subscriptionResponse = restTemplate.exchange(
                "/api/subscriptions", HttpMethod.POST,
                new HttpEntity<>(subscriptionRequest, tenantHeaders(tenantId)), SubscriptionResponse.class);
        assertThat(subscriptionResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        return subscriptionResponse.getBody().id();
    }

    private UUID createCustomer(String tenantId) {
        String suffix = UUID.randomUUID().toString();
        var customerRequest = new CustomerCreateRequest(null, "iso-" + suffix + "@example.com", "Isolation Customer");
        ResponseEntity<CustomerResponse> customerResponse = restTemplate.exchange(
                "/api/customers", HttpMethod.POST,
                new HttpEntity<>(customerRequest, tenantHeaders(tenantId)), CustomerResponse.class);
        assertThat(customerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return customerResponse.getBody().id();
    }
}
