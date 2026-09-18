package dev.lumjahaj.subscription.hub.payment.app;

import com.fasterxml.jackson.databind.JsonNode;
import dev.lumjahaj.subscription.hub.billing.api.dto.InvoiceResponse;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanResponse;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductResponse;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerCreateRequest;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerResponse;
import dev.lumjahaj.subscription.hub.customer.api.dto.PaymentMethodRequest;
import dev.lumjahaj.subscription.hub.dunning.app.DunningJob;
import dev.lumjahaj.subscription.hub.payment.api.dto.PaymentCreateRequest;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reconciliation end to end: a payment whose outcome never came back is
 * settled by asking the provider, and the invoice it was blocking is freed.
 *
 * Every fixture here starts from a real stuck payment rather than a hand-made
 * row — paying with pm_fake_provider_unavailable leaves exactly what an
 * unreachable provider leaves: PENDING, no provider reference, holding the
 * invoice's slot in ux_payment_invoice_in_flight_or_succeeded. What the tests
 * then edit directly is only what the passage of time and a recovered
 * provider would have changed: created_at, and the method the provider is
 * about to report on.
 *
 * The job is invoked directly, its cron disabled in AbstractIntegrationTest,
 * the same way the dunning and billing jobs are driven.
 */
class PaymentReconciliationIntegrationTest extends AbstractIntegrationTest {

    private static final String TENANT = "acme";
    private static final String SUCCEEDS = "pm_card_visa";
    private static final String DECLINED = "pm_card_visa_chargeDeclined";
    private static final String UNAVAILABLE = "pm_fake_provider_unavailable";

    @Autowired
    private PaymentReconciliationJob reconciliationJob;

    @Autowired
    private DunningJob dunningJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void aPaymentWhoseEventNeverArrived_isSettledByAskingTheProvider() {
        // The lost-webhook case: the create landed and we recorded its
        // reference, but the provider's event never reached us.
        Fixture fixture = openInvoice();
        UUID paymentId = stickPayment(fixture.invoiceId());
        setProviderReference(paymentId, "fake_pi_" + paymentId.toString().replace("-", ""));
        setPaymentMethod(paymentId, SUCCEEDS);
        age(paymentId);

        reconciliationJob.run();

        assertThat(paymentStatus(paymentId)).isEqualTo("SUCCEEDED");
        assertThat(invoiceStatus(fixture.invoiceId())).isEqualTo("PAID");
        // Settled through the one settlement path, so the audit trail is the
        // same as any other payment's - SYSTEM, whatever thread found out.
        JsonNode settled = auditHistory(TENANT, "PAYMENT", paymentId).get(0);
        assertThat(settled.get("type").asText()).isEqualTo("PAYMENT_SUCCEEDED");
        assertThat(settled.get("actorType").asText()).isEqualTo("SYSTEM");
    }

    @Test
    void aPaymentTheProviderNeverAcknowledged_isResubmittedUnderTheSameKey() {
        // No reference, so we cannot know whether the create ever landed.
        // It must not be assumed dead: it is re-issued under the same
        // idempotency key, which either recovers the original payment or
        // makes the one we always intended.
        Fixture fixture = openInvoice();
        UUID paymentId = stickPayment(fixture.invoiceId());
        assertThat(providerReference(paymentId)).isNull();
        setPaymentMethod(paymentId, SUCCEEDS);
        age(paymentId);

        reconciliationJob.run();

        assertThat(paymentStatus(paymentId)).isEqualTo("SUCCEEDED");
        assertThat(providerReference(paymentId)).isNotNull();
        assertThat(invoiceStatus(fixture.invoiceId())).isEqualTo("PAID");
    }

    @Test
    void aStuckPaymentBlocksDunningUntilReconciliationClearsIt() {
        // The whole reason this job exists, in one test.
        Fixture fixture = openInvoiceWithMethod(UNAVAILABLE);

        dunningJob.run();
        UUID paymentId = onlyPaymentFor(fixture.invoiceId());
        assertThat(paymentStatus(paymentId)).isEqualTo("PENDING");
        assertThat(attemptCount(fixture.invoiceId())).isEqualTo(1);

        // Dunning is now stuck forever: startAttempt sees a payment in
        // flight and skips the invoice on every run, however due it is.
        makeDue(fixture.invoiceId());
        dunningJob.run();
        assertThat(paymentCountFor(fixture.invoiceId())).isEqualTo(1);
        assertThat(attemptCount(fixture.invoiceId())).isEqualTo(1);

        // The provider comes back and reports the charge was declined.
        setPaymentMethod(paymentId, DECLINED);
        age(paymentId);
        reconciliationJob.run();

        assertThat(paymentStatus(paymentId)).isEqualTo("FAILED");
        assertThat(failureCode(paymentId)).isEqualTo("card_declined");
        assertThat(invoiceStatus(fixture.invoiceId())).isEqualTo("OPEN");
        assertThat(subscriptionStatus(fixture.subscriptionId())).isEqualTo("PAST_DUE");
        // The attempt was counted when it started, before the provider was
        // ever called. Settling it late must not count it a second time.
        assertThat(attemptCount(fixture.invoiceId())).isEqualTo(1);

        // And dunning runs again, which it could not do before.
        setCustomerPaymentMethod(fixture.customerId(), SUCCEEDS);
        makeDue(fixture.invoiceId());
        dunningJob.run();

        assertThat(paymentCountFor(fixture.invoiceId())).isEqualTo(2);
        assertThat(invoiceStatus(fixture.invoiceId())).isEqualTo("PAID");
        assertThat(subscriptionStatus(fixture.subscriptionId())).isEqualTo("ACTIVE");
    }

    @Test
    void aPaymentYoungerThanMinAge_isLeftAlone() {
        // Still mid-flight as far as we know: a webhook is normally seconds
        // away, and reconciling now would race the path that works.
        Fixture fixture = openInvoice();
        UUID paymentId = stickPayment(fixture.invoiceId());
        setPaymentMethod(paymentId, SUCCEEDS);

        reconciliationJob.run();

        assertThat(paymentStatus(paymentId)).isEqualTo("PENDING");
        assertThat(invoiceStatus(fixture.invoiceId())).isEqualTo("OPEN");
    }

    @Test
    void aPaymentTheProviderStillCannotBeReachedFor_staysExactlyAsItWas() {
        // The invariant that protects the customer's card: "unreachable" is
        // not "failed". Marking it failed would free the invoice's slot and
        // let dunning charge again under a new idempotency key, which is a
        // double charge if the original create had in fact landed.
        Fixture fixture = openInvoice();
        UUID paymentId = stickPayment(fixture.invoiceId());
        age(paymentId);

        reconciliationJob.run();

        assertThat(paymentStatus(paymentId)).isEqualTo("PENDING");
        assertThat(providerReference(paymentId)).isNull();
        assertThat(invoiceStatus(fixture.invoiceId())).isEqualTo("OPEN");
        assertThat(subscriptionStatus(fixture.subscriptionId())).isEqualTo("ACTIVE");
        assertThat(auditHistory(TENANT, "PAYMENT", paymentId)).isEmpty();
    }

    @Test
    void aStuckPaymentIsReconciledOnlyUnderItsOwnTenant() {
        Fixture acme = openInvoice(TENANT);
        Fixture demo = openInvoice("demo");
        UUID acmePayment = stickPayment(acme.invoiceId(), TENANT);
        UUID demoPayment = stickPayment(demo.invoiceId(), "demo");
        setPaymentMethod(acmePayment, SUCCEEDS);
        setPaymentMethod(demoPayment, SUCCEEDS);
        age(acmePayment);
        age(demoPayment);

        reconciliationJob.run();

        // Both settle, each under its own tenant: the job runs every active
        // tenant in its own TenantContext, so neither can see the other's row.
        assertThat(paymentStatus(acmePayment)).isEqualTo("SUCCEEDED");
        assertThat(paymentStatus(demoPayment)).isEqualTo("SUCCEEDED");
        assertThat(tenantOf(acmePayment)).isEqualTo(TENANT);
        assertThat(tenantOf(demoPayment)).isEqualTo("demo");
        assertThat(invoiceStatus(acme.invoiceId())).isEqualTo("PAID");
        assertThat(invoiceStatus(demo.invoiceId())).isEqualTo("PAID");
    }

    // ---- stuck-payment helpers ----

    /**
     * Leaves the invoice with a real PENDING payment and no provider
     * reference — what PaymentService does when the provider cannot be
     * reached, and what an awaited webhook looks like from the database.
     */
    private UUID stickPayment(UUID invoiceId) {
        return stickPayment(invoiceId, TENANT);
    }

    private UUID stickPayment(UUID invoiceId, String tenant) {
        HttpHeaders headers = tenantHeaders(tenant);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        restTemplate.exchange("/api/invoices/" + invoiceId + "/payments", HttpMethod.POST,
                new HttpEntity<>(new PaymentCreateRequest(UNAVAILABLE), headers), String.class);
        return onlyPaymentFor(invoiceId);
    }

    /** Simulates min-age having passed. */
    private void age(UUID paymentId) {
        jdbcTemplate.update("UPDATE payment SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofHours(1))), paymentId);
    }

    /** What the provider is about to report, in the fake's own vocabulary. */
    private void setPaymentMethod(UUID paymentId, String paymentMethod) {
        jdbcTemplate.update("UPDATE payment SET payment_method = ? WHERE id = ?", paymentMethod, paymentId);
    }

    private void setProviderReference(UUID paymentId, String reference) {
        jdbcTemplate.update("UPDATE payment SET provider_reference = ? WHERE id = ?", reference, paymentId);
    }

    // ---- state helpers (read straight from the database) ----

    private UUID onlyPaymentFor(UUID invoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM payment WHERE invoice_id = ? ORDER BY created_at DESC LIMIT 1",
                UUID.class, invoiceId);
    }

    private int paymentCountFor(UUID invoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment WHERE invoice_id = ?", Integer.class, invoiceId);
    }

    private String paymentStatus(UUID paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status::text FROM payment WHERE id = ?", String.class, paymentId);
    }

    private String providerReference(UUID paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT provider_reference FROM payment WHERE id = ?", String.class, paymentId);
    }

    private String failureCode(UUID paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT failure_code FROM payment WHERE id = ?", String.class, paymentId);
    }

    private String tenantOf(UUID paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM payment WHERE id = ?", String.class, paymentId);
    }

    private String invoiceStatus(UUID invoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT status::text FROM invoice WHERE id = ?", String.class, invoiceId);
    }

    private String subscriptionStatus(UUID subscriptionId) {
        return jdbcTemplate.queryForObject(
                "SELECT status::text FROM subscription WHERE id = ?", String.class, subscriptionId);
    }

    private int attemptCount(UUID invoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM dunning_state WHERE invoice_id = ?", Integer.class, invoiceId);
    }

    private void makeDue(UUID invoiceId) {
        jdbcTemplate.update("UPDATE dunning_state SET next_attempt_at = ? WHERE invoice_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(1))), invoiceId);
    }

    // ---- fixtures ----

    private record Fixture(UUID customerId, UUID subscriptionId, UUID invoiceId) {
    }

    private Fixture openInvoice() {
        return createFixture(TENANT, null);
    }

    private Fixture openInvoice(String tenant) {
        return createFixture(tenant, null);
    }

    private Fixture openInvoiceWithMethod(String paymentMethod) {
        return createFixture(TENANT, paymentMethod);
    }

    private Fixture createFixture(String tenant, String paymentMethod) {
        String suffix = UUID.randomUUID().toString();

        var productRequest = new ProductCreateRequest("reconcile-product-" + suffix, "Reconcile Product", null);
        ResponseEntity<ProductResponse> product = restTemplate.exchange(
                "/api/products", HttpMethod.POST,
                new HttpEntity<>(productRequest, tenantHeaders(tenant)), ProductResponse.class);
        assertThat(product.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var planRequest = new PlanCreateRequest(
                product.getBody().code(), "reconcile-plan-" + suffix, "Reconcile Plan",
                "MONTH", 1, 2999L, "USD", 0);
        ResponseEntity<PlanResponse> plan = restTemplate.exchange(
                "/api/plans", HttpMethod.POST,
                new HttpEntity<>(planRequest, tenantHeaders(tenant)), PlanResponse.class);
        assertThat(plan.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var customerRequest = new CustomerCreateRequest(
                null, "reconcile-" + suffix + "@example.com", "Reconcile Customer");
        ResponseEntity<CustomerResponse> customer = restTemplate.exchange(
                "/api/customers", HttpMethod.POST,
                new HttpEntity<>(customerRequest, tenantHeaders(tenant)), CustomerResponse.class);
        assertThat(customer.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID customerId = customer.getBody().id();

        if (paymentMethod != null) {
            setCustomerPaymentMethod(customerId, paymentMethod, tenant);
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

    private void setCustomerPaymentMethod(UUID customerId, String paymentMethod) {
        setCustomerPaymentMethod(customerId, paymentMethod, TENANT);
    }

    private void setCustomerPaymentMethod(UUID customerId, String paymentMethod, String tenant) {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/customers/" + customerId + "/payment-method", HttpMethod.PUT,
                new HttpEntity<>(new PaymentMethodRequest(paymentMethod), tenantHeaders(tenant)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
