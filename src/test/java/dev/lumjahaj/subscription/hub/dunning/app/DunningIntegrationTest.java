package dev.lumjahaj.subscription.hub.dunning.app;

import com.fasterxml.jackson.databind.JsonNode;
import dev.lumjahaj.subscription.hub.billing.api.dto.InvoiceResponse;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanResponse;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductResponse;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerCreateRequest;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerResponse;
import dev.lumjahaj.subscription.hub.customer.api.dto.PaymentMethodRequest;
import dev.lumjahaj.subscription.hub.payment.api.dto.PaymentCreateRequest;
import dev.lumjahaj.subscription.hub.payment.api.dto.PaymentResponse;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionCreateRequest;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionResponse;
import dev.lumjahaj.subscription.hub.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dunning end to end: the job charges, settlement decides, and the customer's
 * standing follows.
 *
 * The job is invoked directly rather than waiting for its cron (disabled in
 * AbstractIntegrationTest), and the passage of days is simulated by moving
 * dunning_state.next_attempt_at backwards — the schedule is unit-tested
 * separately in DunningScheduleTest, so nothing here depends on real time.
 *
 * The fake provider settles during the call, which is what makes the
 * consequences observable in one run; with Stripe the same assertions would
 * hold once the webhook arrived.
 */
class DunningIntegrationTest extends AbstractIntegrationTest {

    private static final String TENANT = "acme";
    private static final String SUCCEEDS = "pm_card_visa";
    private static final String DECLINED = "pm_card_visa_chargeDeclined";
    private static final String UNAVAILABLE = "pm_fake_provider_unavailable";

    @Autowired
    private DunningJob dunningJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void aDeclinedInvoice_movesTheSubscriptionPastDueAndSchedulesARetry() {
        Fixture fixture = openInvoiceWithMethod(DECLINED);

        dunningJob.run();

        assertThat(paymentsFor(fixture.invoiceId())).hasSize(1);
        assertThat(paymentsFor(fixture.invoiceId()).get(0).failureCode()).isEqualTo("card_declined");
        assertThat(invoiceStatus(fixture.invoiceId())).isEqualTo("OPEN");
        assertThat(subscriptionStatus(fixture.subscriptionId())).isEqualTo("PAST_DUE");
        assertThat(attemptCount(fixture.invoiceId())).isEqualTo(1);
        assertThat(nextAttemptAt(fixture.invoiceId())).isAfter(Instant.now());
    }

    @Test
    void aSecondRunBeforeTheRetryIsDue_doesNotChargeAgain() {
        Fixture fixture = openInvoiceWithMethod(DECLINED);
        dunningJob.run();

        dunningJob.run();

        assertThat(paymentsFor(fixture.invoiceId())).hasSize(1);
        assertThat(attemptCount(fixture.invoiceId())).isEqualTo(1);
    }

    @Test
    void onceTheRetryIsDue_theNextRunChargesAgain() {
        Fixture fixture = openInvoiceWithMethod(DECLINED);
        dunningJob.run();
        makeDue(fixture.invoiceId());

        dunningJob.run();

        assertThat(paymentsFor(fixture.invoiceId())).hasSize(2);
        assertThat(attemptCount(fixture.invoiceId())).isEqualTo(2);
        assertThat(subscriptionStatus(fixture.subscriptionId())).isEqualTo("PAST_DUE");
    }

    @Test
    void whenTheAttemptsRunOut_theInvoiceIsWrittenOffAndTheSubscriptionCanceled() {
        Fixture fixture = openInvoiceWithMethod(DECLINED);
        dunningJob.run();

        // max-attempts is 4; fast-forward to the last one rather than
        // running the job four times.
        setAttemptCount(fixture.invoiceId(), 3);
        makeDue(fixture.invoiceId());
        dunningJob.run();

        assertThat(invoiceStatus(fixture.invoiceId())).isEqualTo("UNCOLLECTIBLE");
        assertThat(subscriptionStatus(fixture.subscriptionId())).isEqualTo("CANCELED");
        assertThat(canceledAt(fixture.subscriptionId())).isNotNull();
        // The schedule is gone; the failed payments remain as the record.
        assertThat(dunningRowExists(fixture.invoiceId())).isFalse();
        assertThat(paymentsFor(fixture.invoiceId())).hasSize(2);

        JsonNode canceled = auditHistory(TENANT, "SUBSCRIPTION", fixture.subscriptionId()).get(0);
        assertThat(canceled.get("type").asText()).isEqualTo("SUBSCRIPTION_CANCELED");
        assertThat(canceled.get("actorType").asText()).isEqualTo("SYSTEM");
        assertThat(canceled.get("data").get("reason").asText()).isEqualTo("DUNNING_EXHAUSTED");
        assertThat(auditHistory(TENANT, "INVOICE", fixture.invoiceId()))
                .extracting(event -> event.get("type").asText())
                .containsExactly("INVOICE_UNCOLLECTIBLE", "INVOICE_ISSUED");
    }

    @Test
    void aDeclinedManualPayment_isAuditedAsSystemAlthoughAUserStartedIt() {
        // The fake settles inside the admin's own request, so the security
        // context holds a USER. Stripe would settle the same payment in an
        // unauthenticated webhook. The audit log must not depend on which.
        Fixture fixture = openInvoice();

        payManually(fixture.invoiceId(), DECLINED);

        UUID paymentId = paymentsFor(fixture.invoiceId()).get(0).id();
        JsonNode failed = auditHistory(TENANT, "PAYMENT", paymentId).get(0);
        assertThat(failed.get("type").asText()).isEqualTo("PAYMENT_FAILED");
        assertThat(failed.get("actorType").asText()).isEqualTo("SYSTEM");
        assertThat(failed.get("data").get("failureCode").asText()).isEqualTo("card_declined");

        JsonNode pastDue = auditHistory(TENANT, "SUBSCRIPTION", fixture.subscriptionId()).get(0);
        assertThat(pastDue.get("type").asText()).isEqualTo("SUBSCRIPTION_PAST_DUE");
        assertThat(pastDue.get("actorType").asText()).isEqualTo("SYSTEM");
    }

    @Test
    void aRetryThatSucceeds_paysTheInvoiceAndRevivesTheSubscription() {
        Fixture fixture = openInvoiceWithMethod(DECLINED);
        dunningJob.run();
        assertThat(subscriptionStatus(fixture.subscriptionId())).isEqualTo("PAST_DUE");

        // The customer fixes their card, and the next attempt lands.
        setPaymentMethod(fixture.customerId(), SUCCEEDS);
        makeDue(fixture.invoiceId());
        dunningJob.run();

        assertThat(invoiceStatus(fixture.invoiceId())).isEqualTo("PAID");
        assertThat(subscriptionStatus(fixture.subscriptionId())).isEqualTo("ACTIVE");
        assertThat(dunningRowExists(fixture.invoiceId())).isFalse();

        assertThat(auditHistory(TENANT, "SUBSCRIPTION", fixture.subscriptionId()))
                .extracting(event -> event.get("type").asText())
                .startsWith("SUBSCRIPTION_RECOVERED", "SUBSCRIPTION_PAST_DUE");
        assertThat(auditHistory(TENANT, "INVOICE", fixture.invoiceId()).get(0).get("type").asText())
                .isEqualTo("INVOICE_PAID");
    }

    @Test
    void anInvoiceWhoseCustomerHasNoStoredMethod_isLeftAlone() {
        Fixture fixture = openInvoice();

        dunningJob.run();

        assertThat(paymentsFor(fixture.invoiceId())).isEmpty();
        assertThat(invoiceStatus(fixture.invoiceId())).isEqualTo("OPEN");
        assertThat(subscriptionStatus(fixture.subscriptionId())).isEqualTo("ACTIVE");
        // No schedule is started either: nothing was attempted.
        assertThat(dunningRowExists(fixture.invoiceId())).isFalse();
    }

    @Test
    void anInvoiceWithAPaymentInFlight_isSkipped() {
        Fixture fixture = openInvoiceWithMethod(SUCCEEDS);
        // An unreachable provider leaves the payment PENDING, which is what
        // an awaited Stripe webhook looks like.
        payManually(fixture.invoiceId(), UNAVAILABLE);
        assertThat(paymentsFor(fixture.invoiceId())).hasSize(1);

        dunningJob.run();

        assertThat(paymentsFor(fixture.invoiceId())).hasSize(1);
        assertThat(invoiceStatus(fixture.invoiceId())).isEqualTo("OPEN");
    }

    @Test
    void eachTenantsInvoicesAreChargedUnderTheirOwnTenant() {
        Fixture acme = openInvoiceWithMethod(DECLINED, TENANT);
        Fixture demo = openInvoiceWithMethod(DECLINED, "demo");

        dunningJob.run();

        assertThat(paymentsFor(acme.invoiceId(), TENANT)).hasSize(1);
        assertThat(paymentsFor(demo.invoiceId(), "demo")).hasSize(1);
        assertThat(tenantOfPaymentsFor(acme.invoiceId())).containsExactly(TENANT);
        assertThat(tenantOfPaymentsFor(demo.invoiceId())).containsExactly("demo");
        assertThat(subscriptionStatus(acme.subscriptionId())).isEqualTo("PAST_DUE");
        assertThat(subscriptionStatus(demo.subscriptionId())).isEqualTo("PAST_DUE");
    }

    // ---- state helpers (read straight from the database) ----

    private String invoiceStatus(UUID invoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT status::text FROM invoice WHERE id = ?", String.class, invoiceId);
    }

    private String subscriptionStatus(UUID subscriptionId) {
        return jdbcTemplate.queryForObject(
                "SELECT status::text FROM subscription WHERE id = ?", String.class, subscriptionId);
    }

    private Timestamp canceledAt(UUID subscriptionId) {
        return jdbcTemplate.queryForObject(
                "SELECT canceled_at FROM subscription WHERE id = ?", Timestamp.class, subscriptionId);
    }

    private int attemptCount(UUID invoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM dunning_state WHERE invoice_id = ?", Integer.class, invoiceId);
    }

    private Instant nextAttemptAt(UUID invoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT next_attempt_at FROM dunning_state WHERE invoice_id = ?", Timestamp.class, invoiceId)
                .toInstant();
    }

    private boolean dunningRowExists(UUID invoiceId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM dunning_state WHERE invoice_id = ?", Integer.class, invoiceId);
        return count != null && count > 0;
    }

    /** Simulates the retry delay having passed. */
    private void makeDue(UUID invoiceId) {
        jdbcTemplate.update("UPDATE dunning_state SET next_attempt_at = ? WHERE invoice_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(1))), invoiceId);
    }

    private void setAttemptCount(UUID invoiceId, int attempts) {
        jdbcTemplate.update("UPDATE dunning_state SET attempt_count = ? WHERE invoice_id = ?",
                attempts, invoiceId);
    }

    private List<PaymentResponse> paymentsFor(UUID invoiceId) {
        return paymentsFor(invoiceId, TENANT);
    }

    private List<PaymentResponse> paymentsFor(UUID invoiceId, String tenant) {
        ResponseEntity<PaymentResponse[]> response = restTemplate.exchange(
                "/api/invoices/" + invoiceId + "/payments", HttpMethod.GET,
                new HttpEntity<>(tenantHeaders(tenant)), PaymentResponse[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return List.of(response.getBody());
    }

    private List<String> tenantOfPaymentsFor(UUID invoiceId) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT tenant_id FROM payment WHERE invoice_id = ?", String.class, invoiceId);
    }

    private void payManually(UUID invoiceId, String paymentMethod) {
        HttpHeaders headers = tenantHeaders(TENANT);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        restTemplate.exchange("/api/invoices/" + invoiceId + "/payments", HttpMethod.POST,
                new HttpEntity<>(new PaymentCreateRequest(paymentMethod), headers), String.class);
    }

    // ---- fixtures ----

    private record Fixture(UUID customerId, UUID subscriptionId, UUID invoiceId) {
    }

    private Fixture openInvoice() {
        return createFixture(TENANT, null);
    }

    private Fixture openInvoiceWithMethod(String paymentMethod) {
        return createFixture(TENANT, paymentMethod);
    }

    private Fixture openInvoiceWithMethod(String paymentMethod, String tenant) {
        return createFixture(tenant, paymentMethod);
    }

    private Fixture createFixture(String tenant, String paymentMethod) {
        String suffix = UUID.randomUUID().toString();

        var productRequest = new ProductCreateRequest("dunning-product-" + suffix, "Dunning Product", null);
        ResponseEntity<ProductResponse> product = restTemplate.exchange(
                "/api/products", HttpMethod.POST,
                new HttpEntity<>(productRequest, tenantHeaders(tenant)), ProductResponse.class);
        assertThat(product.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var planRequest = new PlanCreateRequest(
                product.getBody().code(), "dunning-plan-" + suffix, "Dunning Plan",
                "MONTH", 1, 2999L, "USD", 0);
        ResponseEntity<PlanResponse> plan = restTemplate.exchange(
                "/api/plans", HttpMethod.POST,
                new HttpEntity<>(planRequest, tenantHeaders(tenant)), PlanResponse.class);
        assertThat(plan.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var customerRequest = new CustomerCreateRequest(
                null, "dunning-" + suffix + "@example.com", "Dunning Customer");
        ResponseEntity<CustomerResponse> customer = restTemplate.exchange(
                "/api/customers", HttpMethod.POST,
                new HttpEntity<>(customerRequest, tenantHeaders(tenant)), CustomerResponse.class);
        assertThat(customer.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID customerId = customer.getBody().id();

        if (paymentMethod != null) {
            setPaymentMethod(customerId, paymentMethod, tenant);
        }

        var subscriptionRequest = new SubscriptionCreateRequest(customerId, plan.getBody().code());
        ResponseEntity<SubscriptionResponse> subscription = restTemplate.exchange(
                "/api/subscriptions", HttpMethod.POST,
                new HttpEntity<>(subscriptionRequest, tenantHeaders(tenant)), SubscriptionResponse.class);
        assertThat(subscription.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID subscriptionId = subscription.getBody().id();

        jdbcTemplate.update("UPDATE subscription SET current_period_end = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), subscriptionId);

        ResponseEntity<InvoiceResponse> invoice = restTemplate.exchange(
                "/api/subscriptions/" + subscriptionId + "/invoices", HttpMethod.POST,
                new HttpEntity<>(tenantHeaders(tenant)), InvoiceResponse.class);
        assertThat(invoice.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        return new Fixture(customerId, subscriptionId, invoice.getBody().id());
    }

    private void setPaymentMethod(UUID customerId, String paymentMethod) {
        setPaymentMethod(customerId, paymentMethod, TENANT);
    }

    private void setPaymentMethod(UUID customerId, String paymentMethod, String tenant) {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/customers/" + customerId + "/payment-method", HttpMethod.PUT,
                new HttpEntity<>(new PaymentMethodRequest(paymentMethod), tenantHeaders(tenant)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
