package dev.lumjahaj.subscription.hub.notification.app;

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
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionCreateRequest;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionResponse;
import dev.lumjahaj.subscription.hub.testsupport.AbstractIntegrationTest;
import dev.lumjahaj.subscription.hub.testsupport.MailpitTestClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
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
import static org.awaitility.Awaitility.await;

/**
 * Dunning's three emails - retry, cancellation and recovery - through the
 * real transport (outbox -> relay -> ElasticMQ -> listener -> Mailpit), the
 * same shape NotificationIntegrationTest proves for the invoice-issued email.
 * Kept in its own class because the fixture (a declining payment method) and
 * the job under test (DunningJob) are both different.
 *
 * The recovery email is also pinned by what must <em>not</em> happen: a first
 * attempt that simply succeeds has a dunning row but no failure the customer
 * was ever told about, so it sends nothing.
 */
class DunningNotificationIntegrationTest extends AbstractIntegrationTest {

    private static final String TENANT = "acme";
    private static final String DECLINED = "pm_card_visa_chargeDeclined";
    private static final String SUCCEEDS = "pm_card_visa";

    @Autowired
    private DunningJob dunningJob;

    @Autowired
    private NotificationRelayJob relayJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final MailpitTestClient mailpit = new MailpitTestClient(mailpitApiUrl());

    @Test
    void aDeclinedPayment_emailsThatItWillBeRetried_thenExhaustingAttemptsEmailsCancellation() {
        UUID subscriptionId = createActiveSubscription(TENANT);
        UUID customerId = customerIdFor(subscriptionId);
        setPaymentMethod(customerId, DECLINED);
        forcePeriodDue(subscriptionId);
        UUID invoiceId = generateInvoice(subscriptionId);
        String invoiceNumber = invoiceNumberFor(subscriptionId);

        dunningJob.run();
        relayJob.run();

        JsonNode failedEmail = awaitMessage("We couldn't collect payment for invoice " + invoiceNumber);
        assertThat(failedEmail.path("Text").asText()).contains(invoiceNumber);

        // max-attempts is 4 (see AbstractIntegrationTest / application.yml);
        // fast-forward to the last one rather than running the job four times.
        jdbcTemplate.update("UPDATE dunning_state SET attempt_count = 3 WHERE invoice_id = ?", invoiceId);
        makeDue(invoiceId);
        dunningJob.run();
        relayJob.run();

        JsonNode canceledEmail = awaitMessage("Your subscription has been canceled");
        assertThat(canceledEmail.path("Text").asText()).contains(invoiceNumber);
    }

    @Test
    void aRecoveredPayment_emailsTheCustomerThatItWentThrough() {
        UUID subscriptionId = createActiveSubscription(TENANT);
        UUID customerId = customerIdFor(subscriptionId);
        setPaymentMethod(customerId, DECLINED);
        forcePeriodDue(subscriptionId);
        UUID invoiceId = generateInvoice(subscriptionId);
        String invoiceNumber = invoiceNumberFor(subscriptionId);

        dunningJob.run();
        assertThat(subscriptionStatus(subscriptionId)).isEqualTo("PAST_DUE");

        // The customer fixes their card and the next attempt lands.
        setPaymentMethod(customerId, SUCCEEDS);
        makeDue(invoiceId);
        dunningJob.run();
        relayJob.run();

        assertThat(subscriptionStatus(subscriptionId)).isEqualTo("ACTIVE");
        JsonNode recoveredEmail = awaitMessage("We've received your payment for invoice " + invoiceNumber);
        assertThat(recoveredEmail.path("Text").asText()).contains(invoiceNumber);
    }

    @Test
    void aFirstAttemptThatSimplySucceeds_sendsNoRecoveryEmail() {
        // The trigger is a real PAST_DUE -> ACTIVE transition, not the mere
        // existence of a dunning row. startAttempt creates one before the
        // provider is called, so a first attempt that succeeds has a row and
        // a recovery counter - but the customer was never told anything was
        // wrong, and telling them they have recovered would be nonsense.
        UUID subscriptionId = createActiveSubscription(TENANT);
        setPaymentMethod(customerIdFor(subscriptionId), SUCCEEDS);
        forcePeriodDue(subscriptionId);
        UUID invoiceId = generateInvoice(subscriptionId);

        dunningJob.run();
        relayJob.run();

        assertThat(invoiceStatus(invoiceId)).isEqualTo("PAID");
        assertThat(subscriptionStatus(subscriptionId)).isEqualTo("ACTIVE");
        // Asserted against the outbox rather than by waiting for mail that
        // should never arrive: absence is only provable where it is recorded.
        assertThat(notificationTypesFor(invoiceId)).containsExactly("INVOICE_ISSUED");
    }

    // ---- polling ----

    private JsonNode awaitMessage(String subjectFragment) {
        return await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> mailpit.findBySubjectContaining(subjectFragment), java.util.Objects::nonNull);
    }

    // ---- state helpers (read straight from the database) ----

    private void makeDue(UUID invoiceId) {
        jdbcTemplate.update("UPDATE dunning_state SET next_attempt_at = ? WHERE invoice_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(1))), invoiceId);
    }

    private void forcePeriodDue(UUID subscriptionId) {
        Instant closedPeriodEnd = Instant.now().minusSeconds(60);
        jdbcTemplate.update(
                "UPDATE subscription SET current_period_end = ?, next_renewal = ? WHERE id = ?",
                Timestamp.from(closedPeriodEnd), Timestamp.from(closedPeriodEnd), subscriptionId);
    }

    private String subscriptionStatus(UUID subscriptionId) {
        return jdbcTemplate.queryForObject(
                "SELECT status::text FROM subscription WHERE id = ?", String.class, subscriptionId);
    }

    private String invoiceStatus(UUID invoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT status::text FROM invoice WHERE id = ?", String.class, invoiceId);
    }

    private List<String> notificationTypesFor(UUID invoiceId) {
        return jdbcTemplate.queryForList(
                "SELECT type FROM notification WHERE invoice_id = ? ORDER BY created_at", String.class, invoiceId);
    }

    private String invoiceNumberFor(UUID subscriptionId) {
        return jdbcTemplate.queryForObject(
                "SELECT number FROM invoice WHERE subscription_id = ?", String.class, subscriptionId);
    }

    private UUID customerIdFor(UUID subscriptionId) {
        return jdbcTemplate.queryForObject(
                "SELECT customer_id FROM subscription WHERE id = ?", UUID.class, subscriptionId);
    }

    // ---- fixtures ----

    private UUID generateInvoice(UUID subscriptionId) {
        ResponseEntity<InvoiceResponse> response = restTemplate.exchange(
                "/api/subscriptions/" + subscriptionId + "/invoices", HttpMethod.POST,
                new HttpEntity<>(tenantHeaders(TENANT)), InvoiceResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private void setPaymentMethod(UUID customerId, String paymentMethod) {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/customers/" + customerId + "/payment-method", HttpMethod.PUT,
                new HttpEntity<>(new PaymentMethodRequest(paymentMethod), tenantHeaders(TENANT)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private UUID createActiveSubscription(String tenant) {
        String suffix = UUID.randomUUID().toString();

        var productRequest = new ProductCreateRequest("dunning-notif-product-" + suffix, "Dunning Notification Test Product", null);
        ResponseEntity<ProductResponse> product = restTemplate.exchange(
                "/api/products", HttpMethod.POST,
                new HttpEntity<>(productRequest, tenantHeaders(tenant)), ProductResponse.class);
        assertThat(product.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var planRequest = new PlanCreateRequest(
                product.getBody().code(), "dunning-notif-plan-" + suffix, "Dunning Notification Test Plan",
                "MONTH", 1, 2999L, "USD", 0);
        ResponseEntity<PlanResponse> plan = restTemplate.exchange(
                "/api/plans", HttpMethod.POST,
                new HttpEntity<>(planRequest, tenantHeaders(tenant)), PlanResponse.class);
        assertThat(plan.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var customerRequest = new CustomerCreateRequest(
                null, "dunning-notif-" + suffix + "@example.com", "Dunning Notification Test Customer");
        ResponseEntity<CustomerResponse> customer = restTemplate.exchange(
                "/api/customers", HttpMethod.POST,
                new HttpEntity<>(customerRequest, tenantHeaders(tenant)), CustomerResponse.class);
        assertThat(customer.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var subscriptionRequest = new SubscriptionCreateRequest(customer.getBody().id(), plan.getBody().code());
        ResponseEntity<SubscriptionResponse> subscription = restTemplate.exchange(
                "/api/subscriptions", HttpMethod.POST,
                new HttpEntity<>(subscriptionRequest, tenantHeaders(tenant)), SubscriptionResponse.class);
        assertThat(subscription.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        return subscription.getBody().id();
    }
}
