package dev.lumjahaj.subscription.hub.subscription.api;

import com.fasterxml.jackson.databind.JsonNode;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanResponse;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductResponse;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerCreateRequest;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerResponse;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionCreateRequest;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionResponse;
import dev.lumjahaj.subscription.hub.subscription.app.SubscriptionRenewalService;
import dev.lumjahaj.subscription.hub.subscription.domain.SubscriptionStatus;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import dev.lumjahaj.subscription.hub.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * End-to-end coverage of subscription creation, state-transition guards,
 * and renewal against a real database - complementing the pure-function
 * unit tests (BillingPeriodsTest, SubscriptionRenewalServiceTest) which
 * don't exercise the real Hibernate load/save path.
 */
class SubscriptionLifecycleIntegrationTest extends AbstractIntegrationTest {

    private static final String TENANT = "acme";

    @Autowired
    private SubscriptionRenewalService renewalService;

    @Test
    void createWithoutTrial_startsActiveWithPeriodEndOneIntervalOut() {
        String planCode = createMonthlyPlan(0);
        UUID customerId = createCustomer();

        ResponseEntity<SubscriptionResponse> response = createSubscription(customerId, planCode);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        SubscriptionResponse body = response.getBody();
        assertThat(body.status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(response.getHeaders().getLocation()).hasPath("/api/subscriptions/" + body.id());
        // Calendar-month arithmetic (BillingPeriods.addInterval uses plusMonths),
        // not a fixed 30-day duration - months vary in length.
        Instant expectedPeriodEnd = Instant.now().atZone(ZoneOffset.UTC).plusMonths(1).toInstant();
        assertThat(body.currentPeriodEnd()).isCloseTo(expectedPeriodEnd, within(10, ChronoUnit.SECONDS));
    }

    @Test
    void getById_returnsTheSubscription() {
        UUID subscriptionId = createActiveSubscription();

        ResponseEntity<SubscriptionResponse> response = restTemplate.exchange(
                "/api/subscriptions/" + subscriptionId, HttpMethod.GET,
                new HttpEntity<>(tenantHeaders(TENANT)), SubscriptionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().id()).isEqualTo(subscriptionId);
    }

    @Test
    void getById_underAnotherTenant_returnsNotFound() {
        UUID subscriptionId = createActiveSubscription();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/subscriptions/" + subscriptionId, HttpMethod.GET,
                new HttpEntity<>(tenantHeaders("demo")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void createWithTrial_startsTrialingWithPeriodEndAtTrialDays() {
        String planCode = createMonthlyPlanWithTrial(14);
        UUID customerId = createCustomer();

        ResponseEntity<SubscriptionResponse> response = createSubscription(customerId, planCode);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        SubscriptionResponse body = response.getBody();
        assertThat(body.status()).isEqualTo(SubscriptionStatus.TRIALING);
        assertThat(body.currentPeriodEnd()).isCloseTo(Instant.now().plus(14, ChronoUnit.DAYS), within(10, ChronoUnit.SECONDS));
    }

    @Test
    void cancelingTwice_secondCallFailsWithInvalidState() {
        UUID subscriptionId = createActiveSubscription();

        ResponseEntity<SubscriptionResponse> first = restTemplate.exchange(
                "/api/subscriptions/" + subscriptionId + "/cancel", HttpMethod.POST,
                new HttpEntity<>(tenantHeaders(TENANT)), SubscriptionResponse.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> second = restTemplate.exchange(
                "/api/subscriptions/" + subscriptionId + "/cancel", HttpMethod.POST,
                new HttpEntity<>(tenantHeaders(TENANT)), String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("INVALID_SUBSCRIPTION_STATE");

        // One cancellation happened and one was refused, so the log shows
        // exactly one, attributed to the admin who made it.
        JsonNode history = auditHistory(TENANT, "SUBSCRIPTION", subscriptionId);
        assertThat(history).extracting(event -> event.get("type").asText())
                .containsExactly("SUBSCRIPTION_CANCELED", "SUBSCRIPTION_CREATED");
        JsonNode canceled = history.get(0);
        assertThat(canceled.get("actorType").asText()).isEqualTo("USER");
        assertThat(canceled.get("actorId").asText()).isNotBlank();
        assertThat(canceled.get("requestId").asText()).isNotBlank();
        assertThat(canceled.get("data").get("from").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void resumingAnActiveSubscription_failsWithInvalidState() {
        UUID subscriptionId = createActiveSubscription();

        ResponseEntity<String> resume = restTemplate.exchange(
                "/api/subscriptions/" + subscriptionId + "/resume", HttpMethod.POST,
                new HttpEntity<>(tenantHeaders(TENANT)), String.class);

        assertThat(resume.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resume.getBody()).contains("INVALID_SUBSCRIPTION_STATE");
    }

    @Test
    void pauseThenResume_roundTripsBackToActive() {
        UUID subscriptionId = createActiveSubscription();

        ResponseEntity<SubscriptionResponse> paused = restTemplate.exchange(
                "/api/subscriptions/" + subscriptionId + "/pause", HttpMethod.POST,
                new HttpEntity<>(tenantHeaders(TENANT)), SubscriptionResponse.class);
        assertThat(paused.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(paused.getBody().status()).isEqualTo(SubscriptionStatus.PAUSED);

        ResponseEntity<SubscriptionResponse> resumed = restTemplate.exchange(
                "/api/subscriptions/" + subscriptionId + "/resume", HttpMethod.POST,
                new HttpEntity<>(tenantHeaders(TENANT)), SubscriptionResponse.class);
        assertThat(resumed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resumed.getBody().status()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void renewalAdvancesPeriodEndAgainstTheRealDatabase() {
        String planCode = createMonthlyPlan(0);
        UUID customerId = createCustomer();
        SubscriptionResponse original = createSubscription(customerId, planCode).getBody();
        Instant farFuture = Instant.now().plus(400, ChronoUnit.DAYS);

        AtomicBoolean changed = new AtomicBoolean();
        TenantContext.runAs(TENANT, () -> changed.set(renewalService.renewIfDue(original.id(), farFuture)));

        assertThat(changed.get()).isTrue();

        ResponseEntity<SubscriptionResponse> after = restTemplate.exchange(
                "/api/subscriptions/" + original.id(), HttpMethod.GET,
                new HttpEntity<>(tenantHeaders(TENANT)), SubscriptionResponse.class);

        // isCloseTo, not isEqualTo: currentPeriodStart round-trips through the DB's
        // timestamptz column (microsecond precision) while `original` came straight
        // off the create response's in-memory value, so sub-millisecond rounding
        // differs even though both represent the same instant.
        assertThat(after.getBody().currentPeriodStart()).isCloseTo(original.currentPeriodEnd(), within(10, ChronoUnit.MILLIS));
        assertThat(after.getBody().currentPeriodEnd()).isAfter(original.currentPeriodEnd());
    }

    private UUID createActiveSubscription() {
        String planCode = createMonthlyPlan(0);
        UUID customerId = createCustomer();
        return createSubscription(customerId, planCode).getBody().id();
    }

    private ResponseEntity<SubscriptionResponse> createSubscription(UUID customerId, String planCode) {
        var request = new SubscriptionCreateRequest(customerId, planCode);
        return restTemplate.exchange("/api/subscriptions", HttpMethod.POST,
                new HttpEntity<>(request, tenantHeaders(TENANT)), SubscriptionResponse.class);
    }

    private String createMonthlyPlan(int trialDays) {
        return createMonthlyPlanWithTrial(trialDays);
    }

    private String createMonthlyPlanWithTrial(int trialDays) {
        String suffix = UUID.randomUUID().toString();

        var productRequest = new ProductCreateRequest("prod-" + suffix, "Lifecycle Test Product", null);
        ResponseEntity<ProductResponse> productResponse = restTemplate.exchange(
                "/api/products", HttpMethod.POST,
                new HttpEntity<>(productRequest, tenantHeaders(TENANT)), ProductResponse.class);
        assertThat(productResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var planRequest = new PlanCreateRequest(
                productRequest.code(), "plan-" + suffix, "Lifecycle Test Plan",
                "MONTH", 1, 1999L, "USD", trialDays);
        ResponseEntity<PlanResponse> planResponse = restTemplate.exchange(
                "/api/plans", HttpMethod.POST,
                new HttpEntity<>(planRequest, tenantHeaders(TENANT)), PlanResponse.class);
        assertThat(planResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        return planResponse.getBody().code();
    }

    private UUID createCustomer() {
        String suffix = UUID.randomUUID().toString();
        var customerRequest = new CustomerCreateRequest(null, "life-" + suffix + "@example.com", "Lifecycle Customer");
        ResponseEntity<CustomerResponse> customerResponse = restTemplate.exchange(
                "/api/customers", HttpMethod.POST,
                new HttpEntity<>(customerRequest, tenantHeaders(TENANT)), CustomerResponse.class);
        assertThat(customerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return customerResponse.getBody().id();
    }
}
