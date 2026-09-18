package dev.lumjahaj.subscription.hub.payment.api;

import dev.lumjahaj.subscription.hub.auth.api.dto.TokenRequest;
import dev.lumjahaj.subscription.hub.auth.api.dto.TokenResponse;
import dev.lumjahaj.subscription.hub.billing.api.dto.InvoiceResponse;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanResponse;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductResponse;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerCreateRequest;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerResponse;
import dev.lumjahaj.subscription.hub.payment.api.dto.PaymentCreateRequest;
import dev.lumjahaj.subscription.hub.payment.api.dto.PaymentResponse;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentEvent;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentEventHandler;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentStatus;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionCreateRequest;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionResponse;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Payments end to end against a real database, through the fake gateway.
 *
 * The fake delivers its settlement event before the create call returns,
 * so every successful payment here also exercises the out-of-order case a
 * real webhook can produce: the event settling a payment whose provider
 * reference hasn't been recorded yet.
 */
class PaymentIntegrationTest extends AbstractIntegrationTest {

    private static final String TENANT = "acme";
    private static final String SUCCEEDS = "pm_card_visa";
    private static final String DECLINED = "pm_card_visa_chargeDeclined";
    private static final String UNAVAILABLE = "pm_fake_provider_unavailable";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PaymentEventHandler paymentEvents;

    @Test
    void pay_withASucceedingMethod_marksThePaymentSucceededAndTheInvoicePaid() {
        InvoiceResponse invoice = createOpenInvoice();

        ResponseEntity<PaymentResponse> response = pay(invoice.id(), SUCCEEDS, newKey(), TENANT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        PaymentResponse payment = response.getBody();
        assertThat(response.getHeaders().getLocation()).hasPath("/api/payments/" + payment.id());
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.amountCents()).isEqualTo(invoice.totalCents());
        assertThat(payment.currency()).isEqualTo(invoice.currency());
        assertThat(payment.provider()).isEqualTo("fake");
        assertThat(payment.providerReference()).startsWith("fake_pi_");

        InvoiceResponse paid = getInvoice(invoice.id());
        assertThat(paid.status().name()).isEqualTo("PAID");
        assertThat(paid.paidAt()).isNotNull();
    }

    @Test
    void pay_withADecliningMethod_recordsAFailedAttemptAndLeavesTheInvoiceOpen() {
        InvoiceResponse invoice = createOpenInvoice();

        ResponseEntity<PaymentResponse> response = pay(invoice.id(), DECLINED, newKey(), TENANT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(response.getBody().failureCode()).isEqualTo("card_declined");
        InvoiceResponse stillOpen = getInvoice(invoice.id());
        assertThat(stillOpen.status().name()).isEqualTo("OPEN");
        assertThat(stillOpen.paidAt()).isNull();
    }

    @Test
    void pay_afterADecline_canBeRetriedWithANewKeyAndSucceed() {
        InvoiceResponse invoice = createOpenInvoice();
        pay(invoice.id(), DECLINED, newKey(), TENANT);

        ResponseEntity<PaymentResponse> retry = pay(invoice.id(), SUCCEEDS, newKey(), TENANT);

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getBody().status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(listPayments(invoice.id())).extracting(PaymentResponse::status)
                .containsExactly(PaymentStatus.FAILED, PaymentStatus.SUCCEEDED);
        // The invoice, not just the payment: a succeeded payment that leaves
        // its invoice OPEN is unbilled revenue, and asserting only the payment
        // statuses is what let that hide here.
        InvoiceResponse paid = getInvoice(invoice.id());
        assertThat(paid.status().name()).isEqualTo("PAID");
        assertThat(paid.paidAt()).isNotNull();
    }

    @Test
    void pay_anInvoiceThatIsAlreadyPaid_returnsConflict() {
        InvoiceResponse invoice = createOpenInvoice();
        pay(invoice.id(), SUCCEEDS, newKey(), TENANT);

        ResponseEntity<String> second = pay(invoice.id(), SUCCEEDS, newKey(), TENANT, String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("INVOICE_NOT_PAYABLE");
    }

    @Test
    void pay_withTheSameIdempotencyKeyTwice_replaysTheFirstPayment() {
        InvoiceResponse invoice = createOpenInvoice();
        String key = newKey();
        PaymentResponse first = pay(invoice.id(), SUCCEEDS, key, TENANT).getBody();

        ResponseEntity<PaymentResponse> replay = pay(invoice.id(), SUCCEEDS, key, TENANT);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().id()).isEqualTo(first.id());
        assertThat(listPayments(invoice.id())).hasSize(1);
    }

    @Test
    void pay_reusingAnIdempotencyKeyForADifferentRequest_returnsConflict() {
        InvoiceResponse invoice = createOpenInvoice();
        String key = newKey();
        pay(invoice.id(), DECLINED, key, TENANT);

        ResponseEntity<String> response = pay(invoice.id(), SUCCEEDS, key, TENANT, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void pay_withoutAnIdempotencyKey_returnsBadRequest() {
        InvoiceResponse invoice = createOpenInvoice();

        ResponseEntity<String> response = pay(invoice.id(), SUCCEEDS, null, TENANT, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("VALIDATION_ERROR");
    }

    @Test
    void pay_withABlankPaymentMethod_returnsBadRequest() {
        InvoiceResponse invoice = createOpenInvoice();

        ResponseEntity<String> response = pay(invoice.id(), " ", newKey(), TENANT, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("VALIDATION_ERROR");
    }

    @Test
    void pay_whenTheProviderIsUnavailable_leavesThePaymentPendingAndBlocksOtherAttempts() {
        InvoiceResponse invoice = createOpenInvoice();
        String key = newKey();

        ResponseEntity<String> response = pay(invoice.id(), UNAVAILABLE, key, TENANT, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("PAYMENT_PROVIDER_UNAVAILABLE");
        assertThat(listPayments(invoice.id())).singleElement().satisfies(payment -> {
            assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.providerReference()).isNull();
        });

        // A different attempt must not reach the provider while one is in flight.
        ResponseEntity<String> other = pay(invoice.id(), SUCCEEDS, newKey(), TENANT, String.class);
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(other.getBody()).contains("PAYMENT_IN_PROGRESS");

        // The same key resubmits to the provider rather than replaying PENDING,
        // which is why this fails again instead of returning 200.
        ResponseEntity<String> retry = pay(invoice.id(), UNAVAILABLE, key, TENANT, String.class);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(listPayments(invoice.id())).hasSize(1);
    }

    @Test
    void concurrentPayments_forTheSameInvoice_chargeExactlyOnce() throws InterruptedException {
        InvoiceResponse invoice = createOpenInvoice();

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    ResponseEntity<String> response = pay(invoice.id(), SUCCEEDS, newKey(), TENANT, String.class);
                    if (response.getStatusCode() == HttpStatus.CREATED) {
                        created.incrementAndGet();
                    } else if (response.getStatusCode() == HttpStatus.CONFLICT) {
                        conflicted.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(created.get()).isEqualTo(1);
        assertThat(conflicted.get()).isEqualTo(threads - 1);
        assertThat(listPayments(invoice.id())).singleElement()
                .extracting(PaymentResponse::status).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void aLateEvent_forAnAlreadySettledPayment_changesNothing() {
        InvoiceResponse invoice = createOpenInvoice();
        PaymentResponse declined = pay(invoice.id(), DECLINED, newKey(), TENANT).getBody();

        TenantContext.runAs(TENANT, () -> paymentEvents.handle(new PaymentEvent(
                "evt_late", "fake", TENANT, declined.id(), declined.providerReference(),
                PaymentEvent.Outcome.SUCCEEDED, null)));

        assertThat(getPayment(declined.id()).status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(getInvoice(invoice.id()).status().name()).isEqualTo("OPEN");
    }

    @Test
    void anEvent_forAnotherTenantThanTheCurrentOne_isRefused() {
        InvoiceResponse invoice = createOpenInvoice();
        PaymentResponse payment = pay(invoice.id(), DECLINED, newKey(), TENANT).getBody();

        assertThatThrownBy(() -> TenantContext.runAs("demo", () -> paymentEvents.handle(new PaymentEvent(
                "evt_cross_tenant", "fake", TENANT, payment.id(), payment.providerReference(),
                PaymentEvent.Outcome.SUCCEEDED, null))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void pay_anotherTenantsInvoice_returnsNotFound() {
        InvoiceResponse invoice = createOpenInvoice();

        ResponseEntity<String> response = pay(invoice.id(), SUCCEEDS, newKey(), "demo", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("INVOICE_NOT_FOUND");
        assertThat(getInvoice(invoice.id()).status().name()).isEqualTo("OPEN");
    }

    @Test
    void getPayment_underAnotherTenant_returnsNotFound() {
        InvoiceResponse invoice = createOpenInvoice();
        UUID paymentId = pay(invoice.id(), SUCCEEDS, newKey(), TENANT).getBody().id();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/payments/" + paymentId, HttpMethod.GET,
                new HttpEntity<>(tenantHeaders("demo")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theSameIdempotencyKey_underTwoTenants_doesNotCollide() {
        String key = newKey();
        InvoiceResponse acmeInvoice = createOpenInvoice();
        InvoiceResponse demoInvoice = createOpenInvoice("demo");

        assertThat(pay(acmeInvoice.id(), SUCCEEDS, key, TENANT).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(pay(demoInvoice.id(), SUCCEEDS, key, "demo").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void support_cannotPay() {
        InvoiceResponse invoice = createOpenInvoice();
        HttpHeaders headers = supportHeaders();
        headers.set("Idempotency-Key", newKey());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/invoices/" + invoice.id() + "/payments", HttpMethod.POST,
                new HttpEntity<>(new PaymentCreateRequest(SUCCEEDS), headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(getInvoice(invoice.id()).status().name()).isEqualTo("OPEN");
    }

    // ---- helpers ----

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private ResponseEntity<PaymentResponse> pay(UUID invoiceId, String method, String key, String tenant) {
        return pay(invoiceId, method, key, tenant, PaymentResponse.class);
    }

    private <T> ResponseEntity<T> pay(UUID invoiceId, String method, String key, String tenant, Class<T> type) {
        HttpHeaders headers = tenantHeaders(tenant);
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        return restTemplate.exchange(
                "/api/invoices/" + invoiceId + "/payments", HttpMethod.POST,
                new HttpEntity<>(new PaymentCreateRequest(method), headers), type);
    }

    private List<PaymentResponse> listPayments(UUID invoiceId) {
        ResponseEntity<PaymentResponse[]> response = restTemplate.exchange(
                "/api/invoices/" + invoiceId + "/payments", HttpMethod.GET,
                new HttpEntity<>(tenantHeaders(TENANT)), PaymentResponse[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return List.of(response.getBody());
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

    private HttpHeaders supportHeaders() {
        ResponseEntity<TokenResponse> login = restTemplate.exchange(
                "/api/auth/token", HttpMethod.POST,
                new HttpEntity<>(new TokenRequest(TENANT, "support@acme.test", "subscriptionhub")),
                TokenResponse.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(login.getBody().token());
        return headers;
    }

    private InvoiceResponse createOpenInvoice() {
        return createOpenInvoice(TENANT);
    }

    private InvoiceResponse createOpenInvoice(String tenant) {
        UUID subscriptionId = createActiveSubscription(tenant);
        jdbcTemplate.update(
                "UPDATE subscription SET current_period_end = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), subscriptionId);

        ResponseEntity<InvoiceResponse> response = restTemplate.exchange(
                "/api/subscriptions/" + subscriptionId + "/invoices", HttpMethod.POST,
                new HttpEntity<>(tenantHeaders(tenant)), InvoiceResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private UUID createActiveSubscription(String tenant) {
        String suffix = UUID.randomUUID().toString();

        var productRequest = new ProductCreateRequest("payment-test-product-" + suffix, "Payment Test Product", null);
        ResponseEntity<ProductResponse> product = restTemplate.exchange(
                "/api/products", HttpMethod.POST,
                new HttpEntity<>(productRequest, tenantHeaders(tenant)), ProductResponse.class);
        assertThat(product.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var planRequest = new PlanCreateRequest(
                product.getBody().code(), "payment-test-plan-" + suffix, "Payment Test Plan",
                "MONTH", 1, 2999L, "USD", 0);
        ResponseEntity<PlanResponse> plan = restTemplate.exchange(
                "/api/plans", HttpMethod.POST,
                new HttpEntity<>(planRequest, tenantHeaders(tenant)), PlanResponse.class);
        assertThat(plan.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var customerRequest = new CustomerCreateRequest(null, "payment-" + suffix + "@example.com", "Payment Test Customer");
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
