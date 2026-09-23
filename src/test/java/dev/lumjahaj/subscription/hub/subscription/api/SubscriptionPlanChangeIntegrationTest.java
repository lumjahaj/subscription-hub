package dev.lumjahaj.subscription.hub.subscription.api;

import com.fasterxml.jackson.databind.JsonNode;
import dev.lumjahaj.subscription.hub.billing.app.BillingCycleJob;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanResponse;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductResponse;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerCreateRequest;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerResponse;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionCreateRequest;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionPlanChangeRequest;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionResponse;
import dev.lumjahaj.subscription.hub.testsupport.AbstractIntegrationTest;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * A plan change is asked for now and takes effect at the next renewal, so the
 * interesting assertions are all about *when* things change rather than that
 * they do.
 *
 * The one that matters most is the money: the closed period must be invoiced at
 * the price the customer was actually on. That holds because BillingCycleJob
 * invoices before it renews and the plan is swapped by the renewal - which is
 * exactly why the swap is not applied when the request arrives.
 */
@SuppressWarnings("unchecked")
class SubscriptionPlanChangeIntegrationTest extends AbstractIntegrationTest {

    private static final String TENANT = "acme";
    private static final String OTHER_TENANT = "demo";

    private static final long BASIC_CENTS = 1999L;
    private static final long PRO_CENTS = 4999L;

    @Autowired
    private BillingCycleJob billingCycleJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void schedulingAChange_movesNothingUntilTheNextRenewal() {
        Fixture fixture = fixture();
        SubscriptionResponse before = getSubscription(fixture.subscriptionId());

        SubscriptionResponse scheduled = schedule(fixture.subscriptionId(), fixture.proPlanCode(), HttpStatus.OK);

        assertThat(scheduled.pendingPlanCode()).isEqualTo(fixture.proPlanCode());
        // Still on the old plan, still in the same period: a client that
        // re-read the subscription would see its billing unchanged.
        assertThat(scheduled.planCode()).isEqualTo(fixture.basicPlanCode());
        assertThat(scheduled.currentPeriodStart()).isEqualTo(before.currentPeriodStart());
        assertThat(scheduled.currentPeriodEnd()).isEqualTo(before.currentPeriodEnd());
        assertThat(scheduled.nextRenewal()).isEqualTo(before.nextRenewal());

        JsonNode event = auditHistory(TENANT, "SUBSCRIPTION", fixture.subscriptionId()).get(0);
        assertThat(event.get("type").asText()).isEqualTo("SUBSCRIPTION_PLAN_CHANGE_SCHEDULED");
        assertThat(event.get("data").get("from").asText()).isEqualTo(fixture.basicPlanCode());
        assertThat(event.get("data").get("to").asText()).isEqualTo(fixture.proPlanCode());
    }

    /**
     * The money assertion, and the reason the swap is deferred at all.
     *
     * InvoiceCalculator reads the plan's price when the invoice is generated.
     * Had the plan been swapped when the request arrived, this closed period -
     * every day of which the customer spent on basic - would have been billed
     * at pro's price.
     */
    @Test
    void theClosedPeriodIsInvoicedAtTheOldPrice_andOnlyThenDoesThePlanChange() {
        Fixture fixture = fixture();
        schedule(fixture.subscriptionId(), fixture.proPlanCode(), HttpStatus.OK);
        forcePeriodDue(fixture.subscriptionId());

        billingCycleJob.run();

        List<Map<String, Object>> invoices = listInvoices(fixture.subscriptionId());
        assertThat(invoices).hasSize(1);
        assertThat(((Number) invoices.get(0).get("totalCents")).longValue()).isEqualTo(BASIC_CENTS);

        SubscriptionResponse after = getSubscription(fixture.subscriptionId());
        assertThat(after.planCode()).isEqualTo(fixture.proPlanCode());
        assertThat(after.pendingPlanCode()).isNull();

        // Applying it is the only record that it ever happened: the pending
        // column has just been cleared.
        JsonNode event = auditHistory(TENANT, "SUBSCRIPTION", fixture.subscriptionId()).get(0);
        assertThat(event.get("type").asText()).isEqualTo("SUBSCRIPTION_PLAN_CHANGED");
        assertThat(event.get("actorType").asText()).isEqualTo("SYSTEM");
        assertThat(event.get("data").get("from").asText()).isEqualTo(fixture.basicPlanCode());
        assertThat(event.get("data").get("to").asText()).isEqualTo(fixture.proPlanCode());
    }

    /**
     * The new period's length comes from the plan being moved onto, not the one
     * being left. Monthly to yearly is the case that shows it: if the renewal
     * took the interval from the old plan, this subscription would be on a
     * yearly plan inside a one-month period and would be billed twelve times a
     * year for it.
     */
    @Test
    void theNewPeriodTakesItsLengthFromTheNewPlan() {
        Fixture fixture = fixture();
        String annualCode = createPlan(fixture.productCode(), "annual", "YEAR", 1, PRO_CENTS);
        schedule(fixture.subscriptionId(), annualCode, HttpStatus.OK);
        Instant closedPeriodEnd = forcePeriodDue(fixture.subscriptionId());

        billingCycleJob.run();

        SubscriptionResponse after = getSubscription(fixture.subscriptionId());
        assertThat(after.planCode()).isEqualTo(annualCode);
        assertThat(after.currentPeriodStart()).isCloseTo(closedPeriodEnd, within(1, ChronoUnit.SECONDS));
        assertThat(Duration.between(after.currentPeriodStart(), after.currentPeriodEnd()).toDays())
                .as("a year, not a month")
                .isGreaterThan(300);
        assertThat(after.nextRenewal()).isEqualTo(after.currentPeriodEnd());
    }

    @Test
    void cancelingAScheduledChange_clearsIt() {
        Fixture fixture = fixture();
        schedule(fixture.subscriptionId(), fixture.proPlanCode(), HttpStatus.OK);

        SubscriptionResponse cleared = cancelChange(fixture.subscriptionId());

        assertThat(cleared.pendingPlanCode()).isNull();
        assertThat(cleared.planCode()).isEqualTo(fixture.basicPlanCode());

        JsonNode event = auditHistory(TENANT, "SUBSCRIPTION", fixture.subscriptionId()).get(0);
        assertThat(event.get("type").asText()).isEqualTo("SUBSCRIPTION_PLAN_CHANGE_CANCELED");
        assertThat(event.get("data").get("canceled").asText()).isEqualTo(fixture.proPlanCode());
    }

    @Test
    void schedulingTheCurrentPlan_clearsTheChangeRatherThanSchedulingANoOp() {
        Fixture fixture = fixture();
        schedule(fixture.subscriptionId(), fixture.proPlanCode(), HttpStatus.OK);

        // "Stay on the plan I am on" is what a caller means by this.
        SubscriptionResponse response = schedule(fixture.subscriptionId(), fixture.basicPlanCode(), HttpStatus.OK);

        assertThat(response.pendingPlanCode()).isNull();
        assertThat(response.planCode()).isEqualTo(fixture.basicPlanCode());
    }

    /**
     * A caller whose intent is already satisfied gets the no-op, not a 409 -
     * and, because a no-op write that bumped updatedAt would invalidate every
     * other client's view for nothing, no write at all.
     */
    @Test
    void schedulingTheSameChangeTwice_writesNothingTheSecondTime() {
        Fixture fixture = fixture();
        schedule(fixture.subscriptionId(), fixture.proPlanCode(), HttpStatus.OK);
        Instant firstUpdatedAt = updatedAt(fixture.subscriptionId());

        schedule(fixture.subscriptionId(), fixture.proPlanCode(), HttpStatus.OK);

        assertThat(updatedAt(fixture.subscriptionId())).isEqualTo(firstUpdatedAt);
        assertThat(auditHistory(TENANT, "SUBSCRIPTION", fixture.subscriptionId()))
                .extracting(event -> event.get("type").asText())
                .containsExactly("SUBSCRIPTION_PLAN_CHANGE_SCHEDULED", "SUBSCRIPTION_CREATED");
    }

    @Test
    void cancelingAChangeThatWasNeverScheduled_writesNothing() {
        Fixture fixture = fixture();
        Instant before = updatedAt(fixture.subscriptionId());

        SubscriptionResponse response = cancelChange(fixture.subscriptionId());

        assertThat(response.pendingPlanCode()).isNull();
        assertThat(updatedAt(fixture.subscriptionId())).isEqualTo(before);
        assertThat(auditHistory(TENANT, "SUBSCRIPTION", fixture.subscriptionId()))
                .extracting(event -> event.get("type").asText())
                .containsExactly("SUBSCRIPTION_CREATED");
    }

    /** A canceled subscription will never renew, so there is no boundary to schedule for. */
    @Test
    void schedulingOnACanceledSubscription_isRefused() {
        Fixture fixture = fixture();
        restTemplate.exchange("/api/subscriptions/" + fixture.subscriptionId() + "/cancel", HttpMethod.POST,
                new HttpEntity<>(tenantHeaders(TENANT)), SubscriptionResponse.class);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/subscriptions/" + fixture.subscriptionId() + "/change-plan", HttpMethod.POST,
                new HttpEntity<>(new SubscriptionPlanChangeRequest(fixture.proPlanCode()), tenantHeaders(TENANT)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_SUBSCRIPTION_STATE");
    }

    @Test
    void schedulingAnUnknownPlan_isNotFound() {
        Fixture fixture = fixture();

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/subscriptions/" + fixture.subscriptionId() + "/change-plan", HttpMethod.POST,
                new HttpEntity<>(new SubscriptionPlanChangeRequest("no-such-plan"), tenantHeaders(TENANT)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aBlankPlanCode_isRejected() {
        Fixture fixture = fixture();

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/subscriptions/" + fixture.subscriptionId() + "/change-plan", HttpMethod.POST,
                new HttpEntity<>(new SubscriptionPlanChangeRequest("  "), tenantHeaders(TENANT)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("VALIDATION_ERROR");
    }

    /**
     * The same proof the rest of the schema gets: another tenant holding a real
     * token cannot reach this subscription even knowing its id.
     */
    @Test
    void anotherTenantCannotScheduleAChangeOnThisSubscription() {
        Fixture fixture = fixture();

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/subscriptions/" + fixture.subscriptionId() + "/change-plan", HttpMethod.POST,
                new HttpEntity<>(new SubscriptionPlanChangeRequest(fixture.proPlanCode()),
                        tenantHeaders(OTHER_TENANT)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getSubscription(fixture.subscriptionId()).pendingPlanCode()).isNull();
    }

    // ---------------------------------------------------------------- helpers

    private record Fixture(UUID subscriptionId, String productCode, String basicPlanCode, String proPlanCode) {
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString();

        var productRequest = new ProductCreateRequest("plan-change-product-" + suffix, "Plan Change Product", null);
        ResponseEntity<ProductResponse> product = restTemplate.exchange(
                "/api/products", HttpMethod.POST,
                new HttpEntity<>(productRequest, tenantHeaders(TENANT)), ProductResponse.class);
        assertThat(product.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String productCode = product.getBody().code();

        String basic = createPlan(productCode, "basic", "MONTH", 1, BASIC_CENTS);
        String pro = createPlan(productCode, "pro", "MONTH", 1, PRO_CENTS);

        var customerRequest = new CustomerCreateRequest(
                null, "plan-change-" + suffix + "@example.com", "Plan Change Customer");
        ResponseEntity<CustomerResponse> customer = restTemplate.exchange(
                "/api/customers", HttpMethod.POST,
                new HttpEntity<>(customerRequest, tenantHeaders(TENANT)), CustomerResponse.class);
        assertThat(customer.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<SubscriptionResponse> subscription = restTemplate.exchange(
                "/api/subscriptions", HttpMethod.POST,
                new HttpEntity<>(new SubscriptionCreateRequest(customer.getBody().id(), basic),
                        tenantHeaders(TENANT)),
                SubscriptionResponse.class);
        assertThat(subscription.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        return new Fixture(subscription.getBody().id(), productCode, basic, pro);
    }

    private String createPlan(String productCode, String name, String unit, int count, long amountCents) {
        String code = name + "-" + UUID.randomUUID();
        var request = new PlanCreateRequest(productCode, code, name, unit, count, amountCents, "USD", 0);
        ResponseEntity<PlanResponse> response = restTemplate.exchange(
                "/api/plans", HttpMethod.POST,
                new HttpEntity<>(request, tenantHeaders(TENANT)), PlanResponse.class);
        assertThat(response.getStatusCode()).as("creating plan %s", code).isEqualTo(HttpStatus.CREATED);
        return response.getBody().code();
    }

    private SubscriptionResponse schedule(UUID subscriptionId, String planCode, HttpStatus expected) {
        ResponseEntity<SubscriptionResponse> response = restTemplate.exchange(
                "/api/subscriptions/" + subscriptionId + "/change-plan", HttpMethod.POST,
                new HttpEntity<>(new SubscriptionPlanChangeRequest(planCode), tenantHeaders(TENANT)),
                SubscriptionResponse.class);
        assertThat(response.getStatusCode()).as("scheduling a change to %s", planCode).isEqualTo(expected);
        return response.getBody();
    }

    private SubscriptionResponse cancelChange(UUID subscriptionId) {
        ResponseEntity<SubscriptionResponse> response = restTemplate.exchange(
                "/api/subscriptions/" + subscriptionId + "/change-plan", HttpMethod.DELETE,
                new HttpEntity<>(tenantHeaders(TENANT)), SubscriptionResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private SubscriptionResponse getSubscription(UUID id) {
        ResponseEntity<SubscriptionResponse> response = restTemplate.exchange(
                "/api/subscriptions/" + id, HttpMethod.GET,
                new HttpEntity<>(tenantHeaders(TENANT)), SubscriptionResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private List<Map<String, Object>> listInvoices(UUID subscriptionId) {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/invoices?subscriptionId=" + subscriptionId, HttpMethod.GET,
                new HttpEntity<>(tenantHeaders(TENANT)), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) response.getBody().get("content");
    }

    /** Same shape as BillingCycleIntegrationTest: both fields move together. */
    private Instant forcePeriodDue(UUID subscriptionId) {
        Instant closedPeriodEnd = Instant.now().minusSeconds(60);
        jdbcTemplate.update(
                "UPDATE subscription SET current_period_end = ?, next_renewal = ? WHERE id = ?",
                Timestamp.from(closedPeriodEnd), Timestamp.from(closedPeriodEnd), subscriptionId);
        return closedPeriodEnd;
    }

    private Instant updatedAt(UUID subscriptionId) {
        return jdbcTemplate.queryForObject(
                "SELECT updated_at FROM subscription WHERE id = ?", Timestamp.class, subscriptionId).toInstant();
    }
}
