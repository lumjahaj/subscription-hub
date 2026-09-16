package dev.lumjahaj.subscription.hub.tenancy;

import dev.lumjahaj.subscription.hub.auth.api.dto.TokenRequest;
import dev.lumjahaj.subscription.hub.auth.api.dto.TokenResponse;
import dev.lumjahaj.subscription.hub.billing.api.dto.InvoiceResponse;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanResponse;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductResponse;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerCreateRequest;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerResponse;
import dev.lumjahaj.subscription.hub.notification.app.NotificationDeliveryService;
import dev.lumjahaj.subscription.hub.notification.app.NotificationRelayJob;
import dev.lumjahaj.subscription.hub.platform.api.dto.TenantCreateRequest;
import dev.lumjahaj.subscription.hub.platform.api.dto.TenantProvisionedResponse;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionCreateRequest;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionResponse;
import dev.lumjahaj.subscription.hub.testsupport.AbstractIntegrationTest;
import dev.lumjahaj.subscription.hub.testsupport.MailpitTestClient;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import org.junit.jupiter.api.BeforeEach;
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
    private NotificationDeliveryService deliveryService;

    @Autowired
    private NotificationRelayJob relayJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final MailpitTestClient mailpit = new MailpitTestClient(mailpitApiUrl());

    private String tenantId;
    private HttpHeaders tenantAdmin;
    private String customerEmail;

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
        setActive(true);
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

    // ---- fixtures (over HTTP, as the new tenant's admin) ----

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
                new HttpEntity<>(new CustomerCreateRequest(null, customerEmail, "Customer"),
                        tenantAdmin),
                CustomerResponse.class);
        assertThat(customer.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<SubscriptionResponse> subscription = restTemplate.exchange(
                "/api/subscriptions", HttpMethod.POST,
                new HttpEntity<>(new SubscriptionCreateRequest(customer.getBody().id(), plan.getBody().code()),
                        tenantAdmin),
                SubscriptionResponse.class);
        assertThat(subscription.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return subscription.getBody().id();
    }
}
