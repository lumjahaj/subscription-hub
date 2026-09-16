package dev.lumjahaj.subscription.hub.notification.app;

import com.fasterxml.jackson.databind.JsonNode;
import dev.lumjahaj.subscription.hub.billing.api.dto.InvoiceResponse;
import dev.lumjahaj.subscription.hub.billing.app.BillingCycleJob;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanResponse;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductResponse;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerCreateRequest;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerResponse;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionCreateRequest;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionResponse;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End to end through the real transport, for the invoice-issued email: outbox
 * row -> relay -> ElasticMQ -> SqsNotificationListener -> Mailpit. Delivery is
 * asynchronous once a message is on the queue, so assertions poll with
 * Awaitility rather than checking immediately after a job returns.
 *
 * Dunning's own emails (payment failed, subscription canceled) are covered
 * separately in DunningNotificationIntegrationTest - a different fixture
 * shape (a declining payment method) and a different job (DunningJob).
 *
 * Duplicate delivery and tenant isolation call NotificationDeliveryService
 * directly instead of going through the queue - that makes them deterministic
 * proofs of the application's own idempotency and tenant checks, rather than
 * a race against the listener's concurrency.
 */
class NotificationIntegrationTest extends AbstractIntegrationTest {

    private static final String TENANT = "acme";
    private static final String OTHER_TENANT = "demo";

    @Autowired
    private BillingCycleJob billingCycleJob;

    @Autowired
    private NotificationRelayJob relayJob;

    @Autowired
    private NotificationDeliveryService deliveryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final MailpitTestClient mailpit = new MailpitTestClient(mailpitApiUrl());

    @Test
    void aBillingCycleInvoice_emailsTheCustomerWithThePdfAttached() {
        UUID subscriptionId = createActiveSubscription(TENANT);
        forcePeriodDue(subscriptionId);

        billingCycleJob.run();
        relayJob.run();

        String invoiceNumber = invoiceNumberFor(subscriptionId);
        JsonNode message = awaitMessage("Invoice " + invoiceNumber + " is ready");

        assertThat(message.path("HTML").asText()).contains(invoiceNumber);
        assertThat(message.path("Text").asText()).contains(invoiceNumber);
        assertThat(message.path("Text").asText()).doesNotContain("<", ">");

        JsonNode attachments = message.path("Attachments");
        assertThat(attachments).hasSize(1);
        assertThat(attachments.get(0).path("FileName").asText()).isEqualTo(invoiceNumber + ".pdf");
        assertThat(attachments.get(0).path("ContentType").asText()).isEqualTo("application/pdf");
    }

    @Test
    void aRedeliveredMessage_doesNotSendTheEmailTwice() {
        UUID subscriptionId = createActiveSubscription(TENANT);
        forcePeriodDue(subscriptionId);
        UUID invoiceId = generateInvoice(subscriptionId);
        UUID notificationId = notificationIdFor("invoice-issued:" + invoiceId);
        String invoiceNumber = invoiceNumberFor(subscriptionId);

        TenantContext.runAs(TENANT, () -> deliveryService.deliver(notificationId));
        TenantContext.runAs(TENANT, () -> deliveryService.deliver(notificationId));

        awaitMessage("Invoice " + invoiceNumber + " is ready");
        long matchingMessages = mailpit.listMessages().stream()
                .filter(m -> m.path("Subject").asText("").contains(invoiceNumber))
                .count();
        assertThat(matchingMessages).isEqualTo(1);
        assertThat(notificationStatus(notificationId)).isEqualTo("SENT");
    }

    @Test
    void aNotificationCannotBeDeliveredUnderTheWrongTenant() {
        UUID subscriptionId = createActiveSubscription(TENANT);
        forcePeriodDue(subscriptionId);
        UUID invoiceId = generateInvoice(subscriptionId);
        UUID notificationId = notificationIdFor("invoice-issued:" + invoiceId);
        String invoiceNumber = invoiceNumberFor(subscriptionId);

        // Claiming the wrong tenant must not deliver acme's notification -
        // findByTenantIdAndId finds nothing, the same isolation every other
        // repository method relies on.
        TenantContext.runAs(OTHER_TENANT, () -> deliveryService.deliver(notificationId));

        assertThat(notificationStatus(notificationId)).isEqualTo("PENDING");
        boolean leaked = mailpit.listMessages().stream()
                .anyMatch(m -> m.path("Subject").asText("").contains(invoiceNumber));
        assertThat(leaked).isFalse();
    }

    // ---- polling ----

    private JsonNode awaitMessage(String subjectFragment) {
        return await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> mailpit.findBySubjectContaining(subjectFragment), java.util.Objects::nonNull);
    }

    // ---- state helpers (read straight from the database) ----

    private UUID notificationIdFor(String dedupKey) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM notification WHERE dedup_key = ?", UUID.class, dedupKey);
    }

    private String notificationStatus(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM notification WHERE id = ?", String.class, id);
    }

    private void forcePeriodDue(UUID subscriptionId) {
        Instant closedPeriodEnd = Instant.now().minusSeconds(60);
        jdbcTemplate.update(
                "UPDATE subscription SET current_period_end = ?, next_renewal = ? WHERE id = ?",
                Timestamp.from(closedPeriodEnd), Timestamp.from(closedPeriodEnd), subscriptionId);
    }

    private String invoiceNumberFor(UUID subscriptionId) {
        return jdbcTemplate.queryForObject(
                "SELECT number FROM invoice WHERE subscription_id = ?", String.class, subscriptionId);
    }

    // ---- fixtures ----

    private UUID generateInvoice(UUID subscriptionId) {
        ResponseEntity<InvoiceResponse> response = restTemplate.exchange(
                "/api/subscriptions/" + subscriptionId + "/invoices", HttpMethod.POST,
                new HttpEntity<>(tenantHeaders(TENANT)), InvoiceResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private UUID createActiveSubscription(String tenant) {
        String suffix = UUID.randomUUID().toString();

        var productRequest = new ProductCreateRequest("notif-product-" + suffix, "Notification Test Product", null);
        ResponseEntity<ProductResponse> product = restTemplate.exchange(
                "/api/products", HttpMethod.POST,
                new HttpEntity<>(productRequest, tenantHeaders(tenant)), ProductResponse.class);
        assertThat(product.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var planRequest = new PlanCreateRequest(
                product.getBody().code(), "notif-plan-" + suffix, "Notification Test Plan",
                "MONTH", 1, 2999L, "USD", 0);
        ResponseEntity<PlanResponse> plan = restTemplate.exchange(
                "/api/plans", HttpMethod.POST,
                new HttpEntity<>(planRequest, tenantHeaders(tenant)), PlanResponse.class);
        assertThat(plan.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var customerRequest = new CustomerCreateRequest(
                null, "notif-" + suffix + "@example.com", "Notification Test Customer");
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
