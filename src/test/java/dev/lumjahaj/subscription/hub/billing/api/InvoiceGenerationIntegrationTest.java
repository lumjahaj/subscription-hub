package dev.lumjahaj.subscription.hub.billing.api;

import dev.lumjahaj.subscription.hub.billing.api.dto.InvoiceResponse;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanEntitlementCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanResponse;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductResponse;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerCreateRequest;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerResponse;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionCreateRequest;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionResponse;
import dev.lumjahaj.subscription.hub.testsupport.AbstractIntegrationTest;
import dev.lumjahaj.subscription.hub.usage.api.dto.UsageRecordRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of invoice generation against a real database. The
 * unit tests (InvoiceCalculatorTest, MeterPriceResolverTest) take the
 * usage_counter <-> subscription period join as given; this is the only
 * place that join is actually exercised, and the only place proving
 * Hibernate binds invoice_status as a native enum and the @OneToMany
 * cascade really persists lines.
 */
class InvoiceGenerationIntegrationTest extends AbstractIntegrationTest {

    private static final String TENANT = "acme";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void generateInvoice_beforeThePeriodCloses_returnsConflict() {
        UUID subscriptionId = createActiveSubscription(createMonthlyPlan());

        ResponseEntity<String> response = generateInvoice(subscriptionId, TENANT, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("INVOICE_PERIOD_NOT_CLOSED");
    }

    @Test
    void generateInvoice_forASubscriptionWithNoUsage_createsAnInvoiceWithOnlyABaseLine() {
        UUID subscriptionId = createActiveSubscription(createMonthlyPlan());
        closePeriod(subscriptionId);

        ResponseEntity<InvoiceResponse> response = generateInvoice(subscriptionId, TENANT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).hasPath("/api/invoices/" + response.getBody().id());
        InvoiceResponse body = response.getBody();
        assertThat(body.number()).matches("INV-\\d{6}");
        assertThat(body.status().name()).isEqualTo("OPEN");
        assertThat(body.currency()).isEqualTo("USD");
        assertThat(body.lines()).hasSize(1);
        assertThat(body.lines().get(0).kind().name()).isEqualTo("BASE");
        assertThat(body.totalCents()).isEqualTo(2999);
    }

    @Test
    void generateInvoice_withMeteredUsageAndAPricedEntitlement_addsAUsageLine() {
        String planCode = createMonthlyPlan();
        addPricedEntitlement(planCode, "api.calls", 100, 5);
        UUID subscriptionId = createActiveSubscription(planCode);
        recordUsage(subscriptionId, "api.calls", "150");
        closePeriod(subscriptionId);

        ResponseEntity<InvoiceResponse> response = generateInvoice(subscriptionId, TENANT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        InvoiceResponse body = response.getBody();
        assertThat(body.lines()).hasSize(2);
        var usageLine = body.lines().get(1);
        assertThat(usageLine.kind().name()).isEqualTo("USAGE");
        assertThat(usageLine.quantity()).isEqualByComparingTo("50");
        assertThat(usageLine.amountCents()).isEqualTo(250);
        assertThat(body.totalCents()).isEqualTo(2999 + 250);
    }

    @Test
    void generateInvoice_withUsageForAnUnpricedMeter_omitsTheUsageLine() {
        String planCode = createMonthlyPlan();
        UUID subscriptionId = createActiveSubscription(planCode);
        recordUsage(subscriptionId, "unpriced.meter", "1000");
        closePeriod(subscriptionId);

        ResponseEntity<InvoiceResponse> response = generateInvoice(subscriptionId, TENANT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().lines()).hasSize(1);
    }

    @Test
    void generateInvoice_totalCents_equalsTheSumOfItsLines() {
        String planCode = createMonthlyPlan();
        addPricedEntitlement(planCode, "api.calls", 0, 3);
        UUID subscriptionId = createActiveSubscription(planCode);
        recordUsage(subscriptionId, "api.calls", "17");
        closePeriod(subscriptionId);

        InvoiceResponse body = generateInvoice(subscriptionId, TENANT).getBody();

        long sumOfLines = body.lines().stream().mapToLong(l -> l.amountCents()).sum();
        assertThat(body.totalCents()).isEqualTo(sumOfLines);
    }

    @Test
    void generateInvoice_twiceForTheSamePeriod_returnsConflict() {
        UUID subscriptionId = createActiveSubscription(createMonthlyPlan());
        closePeriod(subscriptionId);
        assertThat(generateInvoice(subscriptionId, TENANT).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> second = generateInvoice(subscriptionId, TENANT, String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("INVOICE_ALREADY_EXISTS");
    }

    @Test
    void generateInvoice_forTwoSubscriptions_assignsSequentialPerTenantNumbers() {
        String planCode = createMonthlyPlan();
        UUID first = createActiveSubscription(planCode);
        UUID second = createActiveSubscription(planCode);
        closePeriod(first);
        closePeriod(second);

        long firstNumber = numberOf(generateInvoice(first, TENANT).getBody());
        long secondNumber = numberOf(generateInvoice(second, TENANT).getBody());

        assertThat(secondNumber).isEqualTo(firstNumber + 1);
    }

    @Test
    void generateInvoice_forAnUnknownSubscription_returnsNotFound() {
        ResponseEntity<String> response = generateInvoice(UUID.randomUUID(), TENANT, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("SUBSCRIPTION_NOT_FOUND");
    }

    @Test
    void generateInvoice_underAnotherTenant_returnsNotFound() {
        UUID subscriptionId = createActiveSubscription(createMonthlyPlan());
        closePeriod(subscriptionId);

        ResponseEntity<String> response = generateInvoice(subscriptionId, "demo", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getInvoice_underAnotherTenant_returnsNotFound() {
        UUID subscriptionId = createActiveSubscription(createMonthlyPlan());
        closePeriod(subscriptionId);
        UUID invoiceId = generateInvoice(subscriptionId, TENANT).getBody().id();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/invoices/" + invoiceId, HttpMethod.GET,
                new HttpEntity<>(tenantHeaders("demo")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listInvoices_filteredBySubscription_returnsOnlyThatSubscriptionsInvoices() {
        String planCode = createMonthlyPlan();
        UUID withInvoice = createActiveSubscription(planCode);
        UUID withoutInvoice = createActiveSubscription(planCode);
        closePeriod(withInvoice);
        generateInvoice(withInvoice, TENANT);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/invoices?subscriptionId=" + withInvoice, HttpMethod.GET,
                new HttpEntity<>(tenantHeaders(TENANT)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) response.getBody().get("content")).hasSize(1);
    }

    @Test
    void concurrentGeneration_forTheSameSubscription_createsExactlyOneInvoice() throws InterruptedException {
        UUID subscriptionId = createActiveSubscription(createMonthlyPlan());
        closePeriod(subscriptionId);

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
                    ResponseEntity<String> response = generateInvoice(subscriptionId, TENANT, String.class);
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

        ResponseEntity<Map> list = restTemplate.exchange(
                "/api/invoices?subscriptionId=" + subscriptionId, HttpMethod.GET,
                new HttpEntity<>(tenantHeaders(TENANT)), Map.class);
        assertThat((List<?>) list.getBody().get("content")).hasSize(1);
    }

    private long numberOf(InvoiceResponse invoice) {
        return Long.parseLong(invoice.number().substring("INV-".length()));
    }

    private void closePeriod(UUID subscriptionId) {
        jdbcTemplate.update(
                "UPDATE subscription SET current_period_end = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), subscriptionId);
    }

    private ResponseEntity<InvoiceResponse> generateInvoice(UUID subscriptionId, String tenant) {
        return generateInvoice(subscriptionId, tenant, InvoiceResponse.class);
    }

    private <T> ResponseEntity<T> generateInvoice(UUID subscriptionId, String tenant, Class<T> responseType) {
        return restTemplate.exchange(
                "/api/subscriptions/" + subscriptionId + "/invoices", HttpMethod.POST,
                new HttpEntity<>(tenantHeaders(tenant)), responseType);
    }

    private void recordUsage(UUID subscriptionId, String meterKey, String amount) {
        var request = new UsageRecordRequest(meterKey, new BigDecimal(amount));
        ResponseEntity<Object> response = restTemplate.exchange(
                "/api/subscriptions/" + subscriptionId + "/usage", HttpMethod.POST,
                new HttpEntity<>(request, tenantHeaders(TENANT)), Object.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void addPricedEntitlement(String planCode, String meterKey, long includedQuantity, long unitAmountCents) {
        ObjectNode value = JSON.createObjectNode();
        value.put("includedQuantity", includedQuantity);
        value.put("unitAmountCents", unitAmountCents);
        JsonNode value2 = value;
        var request = new PlanEntitlementCreateRequest(meterKey, value2);
        ResponseEntity<Object> response = restTemplate.exchange(
                "/api/plans/" + planCode + "/entitlements", HttpMethod.POST,
                new HttpEntity<>(request, tenantHeaders(TENANT)), Object.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private UUID createActiveSubscription(String planCode) {
        UUID customerId = createCustomer();
        var request = new SubscriptionCreateRequest(customerId, planCode);
        ResponseEntity<SubscriptionResponse> response = restTemplate.exchange(
                "/api/subscriptions", HttpMethod.POST,
                new HttpEntity<>(request, tenantHeaders(TENANT)), SubscriptionResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private String createMonthlyPlan() {
        String suffix = UUID.randomUUID().toString();

        var productRequest = new ProductCreateRequest("billing-test-product-" + suffix, "Billing Test Product", null);
        ResponseEntity<ProductResponse> productResponse = restTemplate.exchange(
                "/api/products", HttpMethod.POST,
                new HttpEntity<>(productRequest, tenantHeaders(TENANT)), ProductResponse.class);
        assertThat(productResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var planRequest = new PlanCreateRequest(
                productResponse.getBody().code(), "billing-test-plan-" + suffix, "Billing Test Plan",
                "MONTH", 1, 2999L, "USD", 0);
        ResponseEntity<PlanResponse> planResponse = restTemplate.exchange(
                "/api/plans", HttpMethod.POST,
                new HttpEntity<>(planRequest, tenantHeaders(TENANT)), PlanResponse.class);
        assertThat(planResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        return planResponse.getBody().code();
    }

    private UUID createCustomer() {
        String suffix = UUID.randomUUID().toString();
        var customerRequest = new CustomerCreateRequest(null, "billing-" + suffix + "@example.com", "Billing Test Customer");
        ResponseEntity<CustomerResponse> customerResponse = restTemplate.exchange(
                "/api/customers", HttpMethod.POST,
                new HttpEntity<>(customerRequest, tenantHeaders(TENANT)), CustomerResponse.class);
        assertThat(customerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return customerResponse.getBody().id();
    }
}
