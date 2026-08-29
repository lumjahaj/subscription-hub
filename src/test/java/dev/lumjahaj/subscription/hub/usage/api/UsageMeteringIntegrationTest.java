package dev.lumjahaj.subscription.hub.usage.api;

import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanResponse;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductResponse;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerCreateRequest;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerResponse;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionCreateRequest;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionResponse;
import dev.lumjahaj.subscription.hub.testsupport.AbstractIntegrationTest;
import dev.lumjahaj.subscription.hub.usage.api.dto.UsageCounterResponse;
import dev.lumjahaj.subscription.hub.usage.api.dto.UsageRecordRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.assertj.core.groups.Tuple;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of usage metering against a real database -
 * including the concurrency guarantee that's the whole point of
 * upsertAndIncrement being a single atomic statement rather than a
 * check-then-save (a plain read-modify-write would lose increments
 * under concurrent requests for the same meter+period).
 */
class UsageMeteringIntegrationTest extends AbstractIntegrationTest {

    private static final String TENANT = "acme";

    @Test
    void recordingUsage_createsACounterWithTheGivenAmount() {
        UUID subscriptionId = createActiveSubscription();

        ResponseEntity<UsageCounterResponse> response = recordUsage(subscriptionId, "emails.sent", "10");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        UsageCounterResponse body = response.getBody();
        assertThat(body.subscriptionId()).isEqualTo(subscriptionId);
        assertThat(body.meterKey()).isEqualTo("emails.sent");
        assertThat(body.amount()).isEqualByComparingTo("10");
    }

    @Test
    void recordingUsageTwice_forTheSameMeter_accumulates() {
        UUID subscriptionId = createActiveSubscription();

        recordUsage(subscriptionId, "emails.sent", "10");
        ResponseEntity<UsageCounterResponse> second = recordUsage(subscriptionId, "emails.sent", "5");

        assertThat(second.getBody().amount()).isEqualByComparingTo("15");

        List<UsageCounterResponse> counters = listUsage(subscriptionId, TENANT).getBody();
        assertThat(counters).hasSize(1);
        assertThat(counters.get(0).amount()).isEqualByComparingTo("15");
    }

    @Test
    void recordingUsage_forDifferentMeters_keepsSeparateCounters() {
        UUID subscriptionId = createActiveSubscription();

        recordUsage(subscriptionId, "emails.sent", "10");
        recordUsage(subscriptionId, "api.calls", "3");

        List<UsageCounterResponse> counters = listUsage(subscriptionId, TENANT).getBody();
        assertThat(counters).hasSize(2);
        assertThat(counters)
                .extracting(UsageCounterResponse::meterKey, c -> c.amount().stripTrailingZeros())
                .containsExactlyInAnyOrder(
                        Tuple.tuple("emails.sent", new BigDecimal("10").stripTrailingZeros()),
                        Tuple.tuple("api.calls", new BigDecimal("3").stripTrailingZeros()));
    }

    @Test
    void recordingUsage_againstUnknownSubscription_returnsNotFound() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/subscriptions/" + UUID.randomUUID() + "/usage", HttpMethod.POST,
                new HttpEntity<>(new UsageRecordRequest("emails.sent", BigDecimal.ONE), tenantHeaders(TENANT)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("SUBSCRIPTION_NOT_FOUND");
    }

    @Test
    void recordingUsage_againstACanceledSubscription_returnsConflict() {
        UUID subscriptionId = createActiveSubscription();
        restTemplate.exchange(
                "/api/subscriptions/" + subscriptionId + "/cancel", HttpMethod.POST,
                new HttpEntity<>(tenantHeaders(TENANT)), SubscriptionResponse.class);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/subscriptions/" + subscriptionId + "/usage", HttpMethod.POST,
                new HttpEntity<>(new UsageRecordRequest("emails.sent", BigDecimal.ONE), tenantHeaders(TENANT)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("INVALID_SUBSCRIPTION_STATE");
    }

    @Test
    void recordingUsage_underAnotherTenant_returnsNotFound() {
        UUID subscriptionId = createActiveSubscription();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/subscriptions/" + subscriptionId + "/usage", HttpMethod.POST,
                new HttpEntity<>(new UsageRecordRequest("emails.sent", BigDecimal.ONE), tenantHeaders("demo")),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listingUsage_underAnotherTenant_returnsNotFound() {
        UUID subscriptionId = createActiveSubscription();
        recordUsage(subscriptionId, "emails.sent", "10");

        ResponseEntity<String> response = listUsage(subscriptionId, "demo", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void concurrentIncrements_forTheSameMeter_convergeToTheExactTotal() throws InterruptedException {
        UUID subscriptionId = createActiveSubscription();
        int threads = 20;
        BigDecimal perRequestAmount = new BigDecimal("3");

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    recordUsage(subscriptionId, "api.calls", perRequestAmount.toPlainString());
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

        List<UsageCounterResponse> counters = listUsage(subscriptionId, TENANT).getBody();
        assertThat(counters).hasSize(1);
        assertThat(counters.get(0).amount())
                .isEqualByComparingTo(perRequestAmount.multiply(BigDecimal.valueOf(threads)));
    }

    private UUID createActiveSubscription() {
        String planCode = createMonthlyPlan();
        UUID customerId = createCustomer();
        var request = new SubscriptionCreateRequest(customerId, planCode);
        ResponseEntity<SubscriptionResponse> response = restTemplate.exchange(
                "/api/subscriptions", HttpMethod.POST,
                new HttpEntity<>(request, tenantHeaders(TENANT)), SubscriptionResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private ResponseEntity<UsageCounterResponse> recordUsage(UUID subscriptionId, String meterKey, String amount) {
        var request = new UsageRecordRequest(meterKey, new BigDecimal(amount));
        ResponseEntity<UsageCounterResponse> response = restTemplate.exchange(
                "/api/subscriptions/" + subscriptionId + "/usage", HttpMethod.POST,
                new HttpEntity<>(request, tenantHeaders(TENANT)), UsageCounterResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response;
    }

    private ResponseEntity<List<UsageCounterResponse>> listUsage(UUID subscriptionId, String tenant) {
        return restTemplate.exchange(
                "/api/subscriptions/" + subscriptionId + "/usage", HttpMethod.GET,
                new HttpEntity<>(tenantHeaders(tenant)),
                new org.springframework.core.ParameterizedTypeReference<List<UsageCounterResponse>>() {
                });
    }

    private <T> ResponseEntity<T> listUsage(UUID subscriptionId, String tenant, Class<T> responseType) {
        return restTemplate.exchange(
                "/api/subscriptions/" + subscriptionId + "/usage", HttpMethod.GET,
                new HttpEntity<>(tenantHeaders(tenant)), responseType);
    }

    private String createMonthlyPlan() {
        String suffix = UUID.randomUUID().toString();

        var productRequest = new ProductCreateRequest("usage-test-product-" + suffix, "Usage Test Product", null);
        ResponseEntity<ProductResponse> productResponse = restTemplate.exchange(
                "/api/products", HttpMethod.POST,
                new HttpEntity<>(productRequest, tenantHeaders(TENANT)), ProductResponse.class);
        assertThat(productResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var planRequest = new PlanCreateRequest(
                productResponse.getBody().code(), "usage-test-plan-" + suffix, "Usage Test Plan",
                "MONTH", 1, 1999L, "USD", 0);
        ResponseEntity<PlanResponse> planResponse = restTemplate.exchange(
                "/api/plans", HttpMethod.POST,
                new HttpEntity<>(planRequest, tenantHeaders(TENANT)), PlanResponse.class);
        assertThat(planResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        return planResponse.getBody().code();
    }

    private UUID createCustomer() {
        String suffix = UUID.randomUUID().toString();
        var customerRequest = new CustomerCreateRequest(null, "usage-" + suffix + "@example.com", "Usage Test Customer");
        ResponseEntity<CustomerResponse> customerResponse = restTemplate.exchange(
                "/api/customers", HttpMethod.POST,
                new HttpEntity<>(customerRequest, tenantHeaders(TENANT)), CustomerResponse.class);
        assertThat(customerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return customerResponse.getBody().id();
    }
}
