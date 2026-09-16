package dev.lumjahaj.subscription.hub.payment.api;

import dev.lumjahaj.subscription.hub.billing.api.dto.InvoiceResponse;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanResponse;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductResponse;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerCreateRequest;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerResponse;
import dev.lumjahaj.subscription.hub.payment.api.dto.PaymentResponse;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentStatus;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionCreateRequest;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionResponse;
import dev.lumjahaj.subscription.hub.testsupport.AbstractIntegrationTest;
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
import java.time.Instant;
import java.util.UUID;

import static dev.lumjahaj.subscription.hub.testsupport.StripeTestSignatures.sign;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Stripe webhook path end to end, with no Stripe account and no network:
 * signature verification is HMAC against a shared secret, so this test mints
 * the payloads and signs them itself, exercising exactly the production code
 * path (StripeWebhook → PaymentWebhookService → PaymentSettlementService).
 *
 * Payments here are inserted directly with provider = 'stripe', because the
 * configured provider in this context is the fake — the API cannot create a
 * Stripe payment without a Stripe account. That is also what makes the
 * provider guard in PaymentSettlementService testable: a Stripe event must
 * not settle a fake payment.
 *
 * The signing secret lives on AbstractIntegrationTest, never here: a
 * subclass-level property override would fork the Spring context cache and
 * start a second set of containers (CLAUDE.md §5).
 */
class StripeWebhookIntegrationTest extends AbstractIntegrationTest {

    private static final String TENANT = "acme";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void aSucceededEvent_settlesThePaymentAndMarksTheInvoicePaid() {
        InvoiceResponse invoice = createOpenInvoice();
        UUID paymentId = insertPendingStripePayment(invoice, TENANT);

        ResponseEntity<String> response = postEvent(succeededEvent("evt_1", TENANT, paymentId, "pi_1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PaymentResponse payment = getPayment(paymentId);
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.providerReference()).isEqualTo("pi_1");
        InvoiceResponse paid = getInvoice(invoice.id());
        assertThat(paid.status().name()).isEqualTo("PAID");
        assertThat(paid.paidAt()).isNotNull();
    }

    @Test
    void aFailedEvent_recordsTheDeclineAndLeavesTheInvoiceOpen() {
        InvoiceResponse invoice = createOpenInvoice();
        UUID paymentId = insertPendingStripePayment(invoice, TENANT);

        ResponseEntity<String> response = postEvent(failedEvent("evt_2", TENANT, paymentId, "pi_2"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PaymentResponse payment = getPayment(paymentId);
        assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.failureCode()).isEqualTo("insufficient_funds");
        assertThat(getInvoice(invoice.id()).status().name()).isEqualTo("OPEN");
    }

    @Test
    void aRedeliveredEvent_changesNothing() {
        InvoiceResponse invoice = createOpenInvoice();
        UUID paymentId = insertPendingStripePayment(invoice, TENANT);
        String payload = succeededEvent("evt_3", TENANT, paymentId, "pi_3");
        postEvent(payload);
        Instant paidAt = getInvoice(invoice.id()).paidAt();

        // Stripe delivers duplicates and does not guarantee order; the
        // second delivery must be a no-op, not a second settlement.
        assertThat(postEvent(payload).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(getPayment(paymentId).status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(getInvoice(invoice.id()).paidAt()).isEqualTo(paidAt);
    }

    @Test
    void aTamperedPayload_isRejectedAndSettlesNothing() {
        InvoiceResponse invoice = createOpenInvoice();
        UUID paymentId = insertPendingStripePayment(invoice, TENANT);
        String payload = succeededEvent("evt_4", TENANT, paymentId, "pi_4");
        HttpHeaders headers = jsonHeaders();
        headers.set("Stripe-Signature", sign(payload.replace("evt_4", "evt_tampered")));

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/webhooks/stripe", HttpMethod.POST, new HttpEntity<>(payload, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("WEBHOOK_SIGNATURE_INVALID");
        assertThat(getPayment(paymentId).status()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void anUnsignedRequest_isRejected() {
        String payload = succeededEvent("evt_5", TENANT, UUID.randomUUID(), "pi_5");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/webhooks/stripe", HttpMethod.POST,
                new HttpEntity<>(payload, jsonHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("WEBHOOK_SIGNATURE_INVALID");
    }

    @Test
    void aStaleSignature_isRejected() {
        InvoiceResponse invoice = createOpenInvoice();
        UUID paymentId = insertPendingStripePayment(invoice, TENANT);
        String payload = succeededEvent("evt_6", TENANT, paymentId, "pi_6");
        HttpHeaders headers = jsonHeaders();
        // Correctly signed, but an hour old: Stripe's 5-minute tolerance is
        // what stops a captured payload being replayed later.
        headers.set("Stripe-Signature", sign(payload, Instant.now().getEpochSecond() - 3600));

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/webhooks/stripe", HttpMethod.POST, new HttpEntity<>(payload, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(getPayment(paymentId).status()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void anUnhandledEventType_isAcceptedAndIgnored() {
        InvoiceResponse invoice = createOpenInvoice();
        UUID paymentId = insertPendingStripePayment(invoice, TENANT);
        String payload = event("evt_7", "charge.refunded", TENANT, paymentId, "pi_7", null);

        // 200, not an error: Stripe retries every non-2xx for three days.
        assertThat(postEvent(payload).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getPayment(paymentId).status()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void anEventWithoutOurMetadata_isAcceptedAndIgnored() {
        String payload = """
                {"id":"evt_8","object":"event","api_version":"2024-06-20","created":%d,
                 "type":"payment_intent.succeeded",
                 "data":{"object":{"id":"pi_8","object":"payment_intent","amount":2999,
                 "currency":"usd","status":"succeeded","metadata":{}}}}
                """.formatted(Instant.now().getEpochSecond());

        assertThat(postEvent(payload).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aStripeEvent_doesNotSettleAFakeProvidersPayment() {
        InvoiceResponse invoice = createOpenInvoice();
        UUID paymentId = insertPendingPayment(invoice, TENANT, "fake");

        assertThat(postEvent(succeededEvent("evt_9", TENANT, paymentId, "pi_9")).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(getPayment(paymentId).status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(getInvoice(invoice.id()).status().name()).isEqualTo("OPEN");
    }

    @Test
    void anEventNamingAnotherTenant_settlesNothing() {
        InvoiceResponse invoice = createOpenInvoice();
        UUID paymentId = insertPendingStripePayment(invoice, TENANT);

        // Signed, so it really is from Stripe, but the tenant in the
        // metadata doesn't own this payment. The tenant-scoped lookup finds
        // nothing rather than settling another tenant's row.
        assertThat(postEvent(succeededEvent("evt_10", "demo", paymentId, "pi_10")).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(getPayment(paymentId).status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(getInvoice(invoice.id()).status().name()).isEqualTo("OPEN");
    }

    // ---- events ----

    private static String succeededEvent(String eventId, String tenantId, UUID paymentId, String intentId) {
        return event(eventId, "payment_intent.succeeded", tenantId, paymentId, intentId, null);
    }

    private static String failedEvent(String eventId, String tenantId, UUID paymentId, String intentId) {
        return event(eventId, "payment_intent.payment_failed", tenantId, paymentId, intentId,
                """
                ,"last_payment_error":{"code":"card_declined","decline_code":"insufficient_funds"}""");
    }

    private static String event(String eventId, String type, String tenantId, UUID paymentId,
                                String intentId, String extraIntentFields) {
        return """
                {"id":"%s","object":"event","api_version":"2024-06-20","created":%d,"type":"%s",
                 "data":{"object":{"id":"%s","object":"payment_intent","amount":2999,"currency":"usd",
                 "status":"succeeded","metadata":{"tenant_id":"%s","payment_id":"%s"}%s}}}
                """.formatted(eventId, Instant.now().getEpochSecond(), type, intentId,
                tenantId, paymentId, extraIntentFields == null ? "" : extraIntentFields);
    }

    private ResponseEntity<String> postEvent(String payload) {
        HttpHeaders headers = jsonHeaders();
        headers.set("Stripe-Signature", sign(payload));
        return restTemplate.exchange(
                "/api/webhooks/stripe", HttpMethod.POST, new HttpEntity<>(payload, headers), String.class);
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    // ---- fixtures ----

    private UUID insertPendingStripePayment(InvoiceResponse invoice, String tenantId) {
        return insertPendingPayment(invoice, tenantId, "stripe");
    }

    private UUID insertPendingPayment(InvoiceResponse invoice, String tenantId, String provider) {
        UUID paymentId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO payment (id, tenant_id, invoice_id, amount_cents, currency, status,
                                             provider, payment_method, idempotency_key, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?::payment_status, ?, ?, ?, now(), now())
                        """,
                paymentId, tenantId, invoice.id(), invoice.totalCents(), invoice.currency(),
                "PENDING", provider, "pm_card_visa", "webhook-test-" + paymentId);
        return paymentId;
    }

    private PaymentResponse getPayment(UUID paymentId) {
        ResponseEntity<PaymentResponse> response = restTemplate.exchange(
                "/api/payments/" + paymentId, HttpMethod.GET,
                new HttpEntity<>(tenantHeaders(TENANT)), PaymentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private InvoiceResponse getInvoice(UUID invoiceId) {
        ResponseEntity<InvoiceResponse> response = restTemplate.exchange(
                "/api/invoices/" + invoiceId, HttpMethod.GET,
                new HttpEntity<>(tenantHeaders(TENANT)), InvoiceResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private InvoiceResponse createOpenInvoice() {
        String suffix = UUID.randomUUID().toString();

        var productRequest = new ProductCreateRequest("webhook-product-" + suffix, "Webhook Product", null);
        ResponseEntity<ProductResponse> product = restTemplate.exchange(
                "/api/products", HttpMethod.POST,
                new HttpEntity<>(productRequest, tenantHeaders(TENANT)), ProductResponse.class);
        assertThat(product.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var planRequest = new PlanCreateRequest(
                product.getBody().code(), "webhook-plan-" + suffix, "Webhook Plan",
                "MONTH", 1, 2999L, "USD", 0);
        ResponseEntity<PlanResponse> plan = restTemplate.exchange(
                "/api/plans", HttpMethod.POST,
                new HttpEntity<>(planRequest, tenantHeaders(TENANT)), PlanResponse.class);
        assertThat(plan.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var customerRequest = new CustomerCreateRequest(
                null, "webhook-" + suffix + "@example.com", "Webhook Customer");
        ResponseEntity<CustomerResponse> customer = restTemplate.exchange(
                "/api/customers", HttpMethod.POST,
                new HttpEntity<>(customerRequest, tenantHeaders(TENANT)), CustomerResponse.class);
        assertThat(customer.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var subscriptionRequest = new SubscriptionCreateRequest(customer.getBody().id(), plan.getBody().code());
        ResponseEntity<SubscriptionResponse> subscription = restTemplate.exchange(
                "/api/subscriptions", HttpMethod.POST,
                new HttpEntity<>(subscriptionRequest, tenantHeaders(TENANT)), SubscriptionResponse.class);
        assertThat(subscription.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        jdbcTemplate.update("UPDATE subscription SET current_period_end = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), subscription.getBody().id());

        ResponseEntity<InvoiceResponse> invoice = restTemplate.exchange(
                "/api/subscriptions/" + subscription.getBody().id() + "/invoices", HttpMethod.POST,
                new HttpEntity<>(tenantHeaders(TENANT)), InvoiceResponse.class);
        assertThat(invoice.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return invoice.getBody();
    }
}
