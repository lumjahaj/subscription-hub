package dev.lumjahaj.subscription.hub.tenancy;

import dev.lumjahaj.subscription.hub.auth.api.dto.TokenRequest;
import dev.lumjahaj.subscription.hub.auth.api.dto.TokenResponse;
import dev.lumjahaj.subscription.hub.billing.api.dto.InvoiceResponse;
import dev.lumjahaj.subscription.hub.billing.app.BillingCycleJob;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanResponse;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductResponse;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerCreateRequest;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerResponse;
import dev.lumjahaj.subscription.hub.dunning.app.DunningJob;
import dev.lumjahaj.subscription.hub.notification.app.NotificationDeliveryService;
import dev.lumjahaj.subscription.hub.notification.app.NotificationRelayJob;
import dev.lumjahaj.subscription.hub.platform.api.dto.TenantCreateRequest;
import dev.lumjahaj.subscription.hub.platform.api.dto.TenantProvisionedResponse;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionCreateRequest;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionResponse;
import dev.lumjahaj.subscription.hub.testsupport.AbstractIntegrationTest;
import dev.lumjahaj.subscription.hub.testsupport.MailpitTestClient;
import dev.lumjahaj.subscription.hub.testsupport.StripeTestSignatures;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Pins what deactivation means for a tenant's automated work: the platform
 * stops acting on the tenant's behalf, records what already happened, loses
 * nothing, and resumes on reactivation.
 *
 * Every test provisions its own tenant through the platform API. Deactivating
 * acme or demo would break every other test class sharing this database.
 */
class TenantDeactivationPolicyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private BillingCycleJob billingCycleJob;

    @Autowired
    private DunningJob dunningJob;

    @Autowired
    private NotificationDeliveryService deliveryService;

    @Autowired
    private NotificationRelayJob relayJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final MailpitTestClient mailpit = new MailpitTestClient(mailpitApiUrl());

    private String tenantId;
    private HttpHeaders tenantAdmin;
    private String customerEmail;
    private UUID customerId;

    @BeforeEach
    void provisionTenant() {
        tenantId = "deact-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<TenantProvisionedResponse> provisioned = restTemplate.exchange(
                "/api/platform/tenants", HttpMethod.POST,
                new HttpEntity<>(new TenantCreateRequest(tenantId, "Deactivation " + tenantId,
                        "admin@" + tenantId + ".test"), platformHeaders()),
                TenantProvisionedResponse.class);
        assertThat(provisioned.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<TokenResponse> login = restTemplate.exchange(
                "/api/auth/token", HttpMethod.POST,
                new HttpEntity<>(new TokenRequest(tenantId, provisioned.getBody().adminEmail(),
                        provisioned.getBody().initialPassword())),
                TokenResponse.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        tenantAdmin = new HttpHeaders();
        tenantAdmin.setBearerAuth(login.getBody().token());
    }

    // ---- billing ----

    @Test
    void aDueSubscription_isNeitherInvoicedNorRenewedWhileInactive_andCatchesUpOnReactivation() {
        UUID subscriptionId = createActiveSubscription();
        forcePeriodDue(subscriptionId);
        Instant dueAt = nextRenewal(subscriptionId);
        setActive(false);

        billingCycleJob.run();

        assertThat(invoiceCount(subscriptionId)).isZero();
        assertThat(nextRenewal(subscriptionId)).isEqualTo(dueAt);

        // Not forgiven: the period is still owed and is billed once the tenant
        // is back. Waiving it is the tenant's decision, not the platform's.
        setActive(true);
        billingCycleJob.run();

        assertThat(invoiceCount(subscriptionId)).isEqualTo(1);
        assertThat(nextRenewal(subscriptionId)).isAfter(dueAt);
    }

    // ---- dunning ----

    @Test
    void anOpenInvoice_isNotChargedWhileInactive_andIsChargedOnReactivation() {
        UUID subscriptionId = createActiveSubscription();
        storePaymentMethod("pm_card_visa");
        forcePeriodDue(subscriptionId);
        UUID invoiceId = generateInvoice(subscriptionId);
        setActive(false);

        dunningJob.run();

        assertThat(paymentCount(invoiceId)).isZero();
        assertThat(invoiceStatus(invoiceId)).isEqualTo("OPEN");

        setActive(true);
        dunningJob.run();

        assertThat(paymentCount(invoiceId)).isEqualTo(1);
        assertThat(invoiceStatus(invoiceId)).isEqualTo("PAID");
    }

    // ---- provider events ----

    @Test
    void aProviderEvent_stillSettlesWhileInactive() {
        // The money already moved at the provider. Refusing to record it would
        // not undo the charge, only make our books disagree with Stripe's.
        UUID subscriptionId = createActiveSubscription();
        forcePeriodDue(subscriptionId);
        UUID invoiceId = generateInvoice(subscriptionId);
        UUID paymentId = insertPendingStripePayment(invoiceId);
        setActive(false);

        String payload = succeededEvent(paymentId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Stripe-Signature", StripeTestSignatures.sign(payload));
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/webhooks/stripe", HttpMethod.POST, new HttpEntity<>(payload, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(paymentStatus(paymentId)).isEqualTo("SUCCEEDED");
        assertThat(invoiceStatus(invoiceId)).isEqualTo("PAID");
    }

    // ---- notifications ----

    @Test
    void anEmailAlreadyOnTheQueue_isHeldWhileTheTenantIsInactive_andSentOnceAfterReactivation() {
        UUID subscriptionId = createActiveSubscription();
        forcePeriodDue(subscriptionId);
        UUID invoiceId = generateInvoice(subscriptionId);
        UUID notificationId = notificationIdFor("invoice-issued:" + invoiceId);

        // The relay already published it before the tenant was deactivated -
        // the one case the relay's own active-tenant filter cannot catch.
        jdbcTemplate.update("UPDATE notification SET status = 'PUBLISHED' WHERE id = ?", notificationId);
        setActive(false);

        // What SqsNotificationListener does with that message. Called directly
        // rather than raced against the real listener, for a deterministic proof.
        TenantContext.runAs(tenantId, () -> deliveryService.deliver(notificationId));

        assertThat(notificationStatus(notificationId))
                .as("handed back to the outbox rather than sent or dropped")
                .isEqualTo("PENDING");
        assertThat(emailsToCustomer()).isZero();

        // The relay skips inactive tenants, so it must not republish yet.
        relayJob.run();
        assertThat(notificationStatus(notificationId)).isEqualTo("PENDING");

        setActive(true);
        relayJob.run();

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> emailsToCustomer() == 1);
        await().atMost(Duration.ofSeconds(5)).until(() -> "SENT".equals(notificationStatus(notificationId)));
        assertThat(emailsToCustomer()).isEqualTo(1);
    }

    @Test
    void aDuplicateOfAnAlreadySentEmail_isNeverHandedBackToTheOutbox() {
        // The ordering trap: if the active check ran before the SENT check, a
        // redelivered duplicate arriving while the tenant is inactive would put
        // a sent email back to PENDING, and reactivation would send it twice.
        UUID subscriptionId = createActiveSubscription();
        forcePeriodDue(subscriptionId);
        UUID invoiceId = generateInvoice(subscriptionId);
        UUID notificationId = notificationIdFor("invoice-issued:" + invoiceId);
        jdbcTemplate.update("UPDATE notification SET status = 'SENT', sent_at = now() WHERE id = ?", notificationId);

        setActive(false);
        TenantContext.runAs(tenantId, () -> deliveryService.deliver(notificationId));

        assertThat(notificationStatus(notificationId)).isEqualTo("SENT");
    }

    // ---- platform actions ----

    private void setActive(boolean active) {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/platform/tenants/" + tenantId + (active ? "/activate" : "/deactivate"), HttpMethod.POST,
                new HttpEntity<>(platformHeaders()), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---- state helpers (read straight from the database) ----

    /**
     * Matched on the recipient, not the subject: invoice numbers restart per
     * tenant, so every provisioned tenant's first invoice is INV-000001 and a
     * subject match would count other tests' emails.
     */
    private long emailsToCustomer() {
        return mailpit.listMessages().stream()
                .filter(m -> {
                    for (var to : m.path("To")) {
                        if (customerEmail.equals(to.path("Address").asText())) {
                            return true;
                        }
                    }
                    return false;
                })
                .count();
    }

    private int invoiceCount(UUID subscriptionId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM invoice WHERE subscription_id = ?", Integer.class, subscriptionId);
    }

    private Instant nextRenewal(UUID subscriptionId) {
        return jdbcTemplate.queryForObject(
                "SELECT next_renewal FROM subscription WHERE id = ?", Timestamp.class, subscriptionId).toInstant();
    }

    private int paymentCount(UUID invoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment WHERE invoice_id = ?", Integer.class, invoiceId);
    }

    private String paymentStatus(UUID paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status::text FROM payment WHERE id = ?", String.class, paymentId);
    }

    private String invoiceStatus(UUID invoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT status::text FROM invoice WHERE id = ?", String.class, invoiceId);
    }

    private UUID notificationIdFor(String dedupKey) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM notification WHERE tenant_id = ? AND dedup_key = ?", UUID.class, tenantId, dedupKey);
    }

    private String notificationStatus(UUID id) {
        return jdbcTemplate.queryForObject("SELECT status FROM notification WHERE id = ?", String.class, id);
    }

    private void forcePeriodDue(UUID subscriptionId) {
        Instant closedPeriodEnd = Instant.now().minusSeconds(60);
        jdbcTemplate.update(
                "UPDATE subscription SET current_period_end = ?, next_renewal = ? WHERE id = ?",
                Timestamp.from(closedPeriodEnd), Timestamp.from(closedPeriodEnd), subscriptionId);
    }

    // ---- fixtures ----

    /**
     * Inserted directly, as StripeWebhookIntegrationTest does: the configured
     * provider is the fake, so the API cannot create a Stripe payment.
     */
    private UUID insertPendingStripePayment(UUID invoiceId) {
        UUID paymentId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO payment (id, tenant_id, invoice_id, amount_cents, currency, status,
                                             provider, payment_method, idempotency_key, created_at, updated_at)
                        SELECT ?, tenant_id, id, total_cents, currency, 'PENDING'::payment_status,
                               'stripe', 'pm_card_visa', ?, now(), now()
                        FROM invoice WHERE id = ?
                        """,
                paymentId, "deactivation-test-" + paymentId, invoiceId);
        return paymentId;
    }

    private String succeededEvent(UUID paymentId) {
        return """
                {"id":"evt_%s","object":"event","api_version":"2024-06-20","created":%d,
                 "type":"payment_intent.succeeded",
                 "data":{"object":{"id":"pi_%s","object":"payment_intent","amount":2999,"currency":"usd",
                 "status":"succeeded","metadata":{"tenant_id":"%s","payment_id":"%s"}}}}
                """.formatted(paymentId, Instant.now().getEpochSecond(), paymentId, tenantId, paymentId);
    }

    // Over HTTP as the new tenant's admin, so these go through the same
    // authentication and tenant resolution a real client does.

    private void storePaymentMethod(String paymentMethod) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(tenantAdmin);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/customers/" + customerId + "/payment-method", HttpMethod.PUT,
                new HttpEntity<>("{\"paymentMethod\":\"" + paymentMethod + "\"}", headers), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.getBody()).isTrue();
    }

    private UUID generateInvoice(UUID subscriptionId) {
        ResponseEntity<InvoiceResponse> response = restTemplate.exchange(
                "/api/subscriptions/" + subscriptionId + "/invoices", HttpMethod.POST,
                new HttpEntity<>(tenantAdmin), InvoiceResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private UUID createActiveSubscription() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        customerEmail = "customer-" + suffix + "@example.com";

        ResponseEntity<ProductResponse> product = restTemplate.exchange(
                "/api/products", HttpMethod.POST,
                new HttpEntity<>(new ProductCreateRequest("product-" + suffix, "Product", null), tenantAdmin),
                ProductResponse.class);
        assertThat(product.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<PlanResponse> plan = restTemplate.exchange(
                "/api/plans", HttpMethod.POST,
                new HttpEntity<>(new PlanCreateRequest(product.getBody().code(), "plan-" + suffix, "Plan",
                        "MONTH", 1, 2999L, "USD", 0), tenantAdmin),
                PlanResponse.class);
        assertThat(plan.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<CustomerResponse> customer = restTemplate.exchange(
                "/api/customers", HttpMethod.POST,
                new HttpEntity<>(new CustomerCreateRequest(null, customerEmail, "Customer"), tenantAdmin),
                CustomerResponse.class);
        assertThat(customer.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        customerId = customer.getBody().id();

        ResponseEntity<SubscriptionResponse> subscription = restTemplate.exchange(
                "/api/subscriptions", HttpMethod.POST,
                new HttpEntity<>(new SubscriptionCreateRequest(customerId, plan.getBody().code()), tenantAdmin),
                SubscriptionResponse.class);
        assertThat(subscription.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return subscription.getBody().id();
    }
}
