package dev.lumjahaj.subscription.hub.billing.app;

import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanResponse;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductResponse;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerCreateRequest;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerResponse;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionCreateRequest;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionResponse;
import dev.lumjahaj.subscription.hub.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Proves the one thing no unit test can express: that BillingCycleJob
 * invoices a subscription's closed period BEFORE renewing it. Renewal
 * overwrites currentPeriodStart the moment it runs (see
 * SubscriptionRenewalService), so if the ordering were reversed, the
 * period this test closes would never be billed.
 */
@SuppressWarnings("unchecked")
class BillingCycleIntegrationTest extends AbstractIntegrationTest {

    private static final String TENANT = "acme";

    @Autowired
    private BillingCycleJob billingCycleJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void billingCycle_forAClosedPeriod_invoicesBeforeRenewing() {
        UUID subscriptionId = createActiveSubscription();
        Instant closedPeriodEnd = forcePeriodDue(subscriptionId);

        billingCycleJob.run();

        List<Map<String, Object>> invoices = listInvoices(subscriptionId);
        assertThat(invoices).hasSize(1);
        Instant invoicedPeriodEnd = Instant.parse((String) invoices.get(0).get("periodEnd"));
        assertThat(invoicedPeriodEnd).isCloseTo(closedPeriodEnd, within(1, ChronoUnit.SECONDS));

        SubscriptionResponse subscription = getSubscription(subscriptionId);
        // The subscription only advances to a new period start equal to
        // the period the invoice just covers if invoicing ran first -
        // had renewal run first, the invoice above would have covered
        // the wrong (new, still-open) period instead.
        assertThat(subscription.currentPeriodStart()).isCloseTo(closedPeriodEnd, within(1, ChronoUnit.SECONDS));
        assertThat(subscription.currentPeriodEnd()).isAfter(closedPeriodEnd);
    }

    @Test
    void billingCycle_runTwice_doesNotDoubleInvoice() {
        UUID subscriptionId = createActiveSubscription();
        forcePeriodDue(subscriptionId);

        billingCycleJob.run();
        billingCycleJob.run();

        assertThat(listInvoices(subscriptionId)).hasSize(1);
    }

    @Test
    void billingCycle_forAClosedPeriod_alsoGeneratesThePdf() {
        UUID subscriptionId = createActiveSubscription();
        forcePeriodDue(subscriptionId);

        billingCycleJob.run();

        List<Map<String, Object>> invoices = listInvoices(subscriptionId);
        assertThat(invoices).hasSize(1);
        assertThat(invoices.get(0).get("pdfAvailable")).isEqualTo(true);
    }

    /**
     * BillingCycleJob selects subscriptions the same way RenewalJob did -
     * by nextRenewal - so both nextRenewal and currentPeriodEnd have to
     * move into the past together for a subscription to be picked up.
     */
    private Instant forcePeriodDue(UUID subscriptionId) {
        Instant closedPeriodEnd = Instant.now().minusSeconds(60);
        jdbcTemplate.update(
                "UPDATE subscription SET current_period_end = ?, next_renewal = ? WHERE id = ?",
                Timestamp.from(closedPeriodEnd), Timestamp.from(closedPeriodEnd), subscriptionId);
        return closedPeriodEnd;
    }

    private List<Map<String, Object>> listInvoices(UUID subscriptionId) {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/invoices?subscriptionId=" + subscriptionId, HttpMethod.GET,
                new HttpEntity<>(tenantHeaders(TENANT)), Map.class);
        return (List<Map<String, Object>>) response.getBody().get("content");
    }

    private SubscriptionResponse getSubscription(UUID id) {
        ResponseEntity<SubscriptionResponse> response = restTemplate.exchange(
                "/api/subscriptions/" + id, HttpMethod.GET,
                new HttpEntity<>(tenantHeaders(TENANT)), SubscriptionResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private UUID createActiveSubscription() {
        String suffix = UUID.randomUUID().toString();

        var productRequest = new ProductCreateRequest("cycle-test-product-" + suffix, "Cycle Test Product", null);
        ResponseEntity<ProductResponse> productResponse = restTemplate.exchange(
                "/api/products", HttpMethod.POST,
                new HttpEntity<>(productRequest, tenantHeaders(TENANT)), ProductResponse.class);
        assertThat(productResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var planRequest = new PlanCreateRequest(
                productResponse.getBody().code(), "cycle-test-plan-" + suffix, "Cycle Test Plan",
                "MONTH", 1, 1999L, "USD", 0);
        ResponseEntity<PlanResponse> planResponse = restTemplate.exchange(
                "/api/plans", HttpMethod.POST,
                new HttpEntity<>(planRequest, tenantHeaders(TENANT)), PlanResponse.class);
        assertThat(planResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var customerRequest = new CustomerCreateRequest(null, "cycle-" + suffix + "@example.com", "Cycle Test Customer");
        ResponseEntity<CustomerResponse> customerResponse = restTemplate.exchange(
                "/api/customers", HttpMethod.POST,
                new HttpEntity<>(customerRequest, tenantHeaders(TENANT)), CustomerResponse.class);
        assertThat(customerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var subscriptionRequest = new SubscriptionCreateRequest(customerResponse.getBody().id(), planResponse.getBody().code());
        ResponseEntity<SubscriptionResponse> subscriptionResponse = restTemplate.exchange(
                "/api/subscriptions", HttpMethod.POST,
                new HttpEntity<>(subscriptionRequest, tenantHeaders(TENANT)), SubscriptionResponse.class);
        assertThat(subscriptionResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        return subscriptionResponse.getBody().id();
    }
}
