package dev.lumjahaj.subscription.hub.subscription.app;

import com.fasterxml.jackson.databind.JsonNode;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductCreateRequest;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerCreateRequest;
import dev.lumjahaj.subscription.hub.dunning.app.DunningService;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionCreateRequest;
import dev.lumjahaj.subscription.hub.subscription.domain.SubscriptionRepository;
import dev.lumjahaj.subscription.hub.subscription.infra.jpa.SubscriptionEntity;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import dev.lumjahaj.subscription.hub.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two writers, one subscription: each test forces the race rather than hoping
 * threads collide.
 *
 * A transaction first reads the subscription, so its persistence context holds
 * the state as it was. Another connection then commits a competing change.
 * Only then does the real service method run, inside that stale transaction -
 * exactly the window in which the old load-modify-save wrote its stale read
 * back over the other change. Each of these was a lost update before
 * transitions became compare-and-set:
 * - a renewal reinstating a subscription the customer had just canceled
 * - dunning marking PAST_DUE over a pause
 * - a recovery reactivating a subscription canceled while the payment settled
 * - a request applying a transition from a status that no longer holds
 *
 * The competing write goes through JdbcTemplate on another thread: on this
 * thread it would join the open JPA transaction instead of committing first.
 */
class SubscriptionTransitionRaceIntegrationTest extends AbstractIntegrationTest {

    private static final String TENANT = "acme";

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SubscriptionRepository subscriptions;

    @Autowired
    private SubscriptionRenewalService renewalService;

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private DunningService dunningService;

    @Test
    void aCancellationCommittedWhileRenewing_isNotUndoneByTheRenewal() {
        UUID id = createSubscription();
        // Microseconds, the precision Postgres stores, so the comparison below is exact.
        Instant dueEnd = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        jdbcTemplate.update("UPDATE subscription SET current_period_end = ?, next_renewal = ? WHERE id = ?",
                Timestamp.from(dueEnd), Timestamp.from(dueEnd), id);

        boolean renewed = inStaleTransaction(id, "UPDATE subscription SET status = 'CANCELED', canceled_at = now() WHERE id = ?",
                () -> renewalService.renewIfDue(id, Instant.now()));

        assertThat(renewed).isFalse();
        assertThat(status(id)).isEqualTo("CANCELED");
        assertThat(jdbcTemplate.queryForObject("SELECT current_period_end FROM subscription WHERE id = ?", Timestamp.class, id)
                .toInstant()).as("the canceled subscription's period is not advanced").isEqualTo(dueEnd);
    }

    @Test
    void aPauseCommittedWhileAPaymentFailureSettles_isNotOverwrittenWithPastDue() {
        UUID id = createSubscription();
        UUID invoiceId = invoiceFor(id);

        inStaleTransaction(id, "UPDATE subscription SET status = 'PAUSED' WHERE id = ?",
                () -> { dunningService.onPaymentFailed(invoiceId, "card_declined"); return null; });

        assertThat(status(id)).isEqualTo("PAUSED");
        assertThat(auditTypes(id)).doesNotContain("SUBSCRIPTION_PAST_DUE");
    }

    @Test
    void aCancellationCommittedWhileARecoverySettles_isNotReactivated() {
        UUID id = createSubscription();
        UUID invoiceId = invoiceFor(id);
        jdbcTemplate.update("UPDATE subscription SET status = 'PAST_DUE' WHERE id = ?", id);

        inStaleTransaction(id, "UPDATE subscription SET status = 'CANCELED', canceled_at = now() WHERE id = ?",
                () -> { dunningService.onPaymentSucceeded(invoiceId); return null; });

        assertThat(status(id)).isEqualTo("CANCELED");
        assertThat(auditTypes(id)).doesNotContain("SUBSCRIPTION_RECOVERED");
    }

    @Test
    void aRequestTransitionFromAStatusThatNoLongerHolds_isRefused() {
        UUID id = createSubscription();

        assertThatThrownBy(() -> inStaleTransaction(id, "UPDATE subscription SET status = 'PAST_DUE' WHERE id = ?",
                () -> subscriptionService.pause(id)))
                .isInstanceOf(InvalidSubscriptionStateException.class)
                .hasMessageContaining("PAST_DUE");

        assertThat(status(id)).isEqualTo("PAST_DUE");
    }

    @Test
    void aRequestTransitionStillAllowedFromTheNewStatus_appliesAndRecordsTheRealFromStatus() {
        UUID id = createSubscription();

        inStaleTransaction(id, "UPDATE subscription SET status = 'PAST_DUE' WHERE id = ?",
                () -> subscriptionService.cancel(id));

        assertThat(status(id)).isEqualTo("CANCELED");
        // The request read ACTIVE; the row it actually changed was PAST_DUE.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT data->>'from' FROM audit_event WHERE entity_id = ? AND type = 'SUBSCRIPTION_CANCELED'",
                String.class, id.toString())).isEqualTo("PAST_DUE");
    }

    /**
     * A renewal computes the new period from the plan it read. If a plan change
     * commits in between and the renewal applied anyway, the SET clause would
     * write back the plan the renewal read and clear pending_plan_id - silently
     * discarding a change the customer asked for and was told was scheduled.
     *
     * Driven through renewIfCurrent rather than renewIfDue, which the other
     * tests here use, because the race is between the renewal's *read* and its
     * *update*: renewIfDue does both, and calling it inside this harness would
     * make it re-read a subscription this transaction had already loaded -
     * something BillingCycleJob never does, since every renewal gets its own
     * transaction. The arguments below are exactly what renewIfDue computes
     * from what it read: no pending change, so stay on the current plan.
     */
    @Test
    void aPlanChangeCommittedWhileRenewing_isNotSilentlyDiscarded() {
        PlanChangeFixture f = planChangeFixture();
        Instant dueEnd = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        jdbcTemplate.update("UPDATE subscription SET current_period_end = ?, next_renewal = ? WHERE id = ?",
                Timestamp.from(dueEnd), Timestamp.from(dueEnd), f.subscriptionId());

        TenantContext.runAs(TENANT, () -> transactionTemplate.executeWithoutResult(tx -> {
            SubscriptionEntity read = subscriptions.findByTenantIdAndId(TENANT, f.subscriptionId()).orElseThrow();
            assertThat(read.getPendingPlan()).as("nothing was scheduled when the renewal read it").isNull();

            commitOnAnotherConnection(
                    "UPDATE subscription SET pending_plan_id = '" + f.targetPlanId() + "' WHERE id = ?",
                    f.subscriptionId());

            boolean renewed = subscriptions.renewIfCurrent(TENANT, f.subscriptionId(),
                    read.getStatus(), read.getCurrentPeriodEnd(),
                    null, read.getPlan(),
                    read.getCurrentPeriodEnd(), read.getCurrentPeriodEnd().plus(30, ChronoUnit.DAYS));

            assertThat(renewed).as("the guard on the pending plan makes it a no-op").isFalse();
        }));

        assertThat(pendingPlanId(f.subscriptionId())).as("the scheduled change survives the miss")
                .isEqualTo(f.targetPlanId());
        assertThat(periodEnd(f.subscriptionId())).as("nothing advanced").isEqualTo(dueEnd);

        // The next run reads the pending plan and applies both together.
        TenantContext.runAs(TENANT, () -> renewalService.renewIfDue(f.subscriptionId(), Instant.now()));

        assertThat(planCode(f.subscriptionId())).isEqualTo(f.targetPlanCode());
        assertThat(pendingPlanId(f.subscriptionId())).isNull();
        assertThat(Duration.between(dueEnd, periodEnd(f.subscriptionId())).toDays())
                .as("a year, from the plan it moved onto - not a month from the one it left")
                .isGreaterThan(300);
    }

    /**
     * The seam every compare-and-set retry goes through, and the one place a
     * changed pending_plan_id is genuinely re-read inside a transaction that
     * already loaded the subscription.
     *
     * findByTenantIdAndId join-fetches pendingPlan, and a query never
     * overwrites an entity the persistence context already holds, so querying
     * without refreshing first threw EntityFilterException - a 500 in place of
     * a retry, for two admins changing the same subscription at once. Scalar
     * changes never had this problem, which is why the old order held until a
     * nullable association existed.
     */
    @Test
    void reReadingAfterAMissedUpdate_seesAPlanChangeCommittedInBetween() {
        PlanChangeFixture f = planChangeFixture();

        TenantContext.runAs(TENANT, () -> transactionTemplate.executeWithoutResult(tx -> {
            assertThat(subscriptions.findByTenantIdAndId(TENANT, f.subscriptionId())).isPresent();

            commitOnAnotherConnection(
                    "UPDATE subscription SET pending_plan_id = '" + f.targetPlanId() + "' WHERE id = ?",
                    f.subscriptionId());

            SubscriptionEntity current = subscriptions.findCurrentByTenantIdAndId(TENANT, f.subscriptionId())
                    .orElseThrow();

            assertThat(current.getPendingPlan()).isNotNull();
            assertThat(current.getPendingPlan().getId()).isEqualTo(f.targetPlanId());
        }));
    }

    /** A subscription canceled in between will never renew, so there is nothing to schedule for. */
    @Test
    void aCancellationCommittedWhileSchedulingAPlanChange_isRefused() {
        PlanChangeFixture f = planChangeFixture();

        assertThatThrownBy(() -> inStaleTransaction(f.subscriptionId(),
                "UPDATE subscription SET status = 'CANCELED', canceled_at = now() WHERE id = ?",
                () -> subscriptionService.schedulePlanChange(f.subscriptionId(), f.targetPlanCode())))
                .isInstanceOf(InvalidSubscriptionStateException.class);

        assertThat(status(f.subscriptionId())).isEqualTo("CANCELED");
        assertThat(pendingPlanId(f.subscriptionId())).isNull();
        assertThat(auditTypes(f.subscriptionId())).doesNotContain("SUBSCRIPTION_PLAN_CHANGE_SCHEDULED");
    }

    // ---- helpers ----

    /**
     * Opens a transaction, reads the subscription into it, commits
     * competingUpdate from another connection, then runs the action in the
     * now-stale transaction.
     */
    private <T> T inStaleTransaction(UUID subscriptionId, String competingUpdate, java.util.function.Supplier<T> action) {
        Object[] result = new Object[1];
        TenantContext.runAs(TENANT, () -> transactionTemplate.executeWithoutResult(status -> {
            assertThat(subscriptions.findByTenantIdAndId(TENANT, subscriptionId)).isPresent();
            commitOnAnotherConnection(competingUpdate, subscriptionId);
            result[0] = action.get();
        }));
        @SuppressWarnings("unchecked")
        T typed = (T) result[0];
        return typed;
    }

    private void commitOnAnotherConnection(String sql, UUID subscriptionId) {
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            other.submit(() -> jdbcTemplate.update(sql, subscriptionId)).get();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        } finally {
            other.shutdownNow();
        }
    }

    private String status(UUID id) {
        return jdbcTemplate.queryForObject("SELECT status::text FROM subscription WHERE id = ?", String.class, id);
    }

    private List<String> auditTypes(UUID id) {
        return jdbcTemplate.queryForList("SELECT type FROM audit_event WHERE entity_id = ?", String.class, id.toString());
    }

    private UUID createSubscription() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        post("/api/products", new ProductCreateRequest("race-product-" + suffix, "Race Product", null));
        post("/api/plans", new PlanCreateRequest("race-product-" + suffix, "race-plan-" + suffix, "Race Plan",
                "MONTH", 1, 900L, "EUR", 0));
        UUID customerId = UUID.fromString(post("/api/customers",
                new CustomerCreateRequest(null, "race-" + suffix + "@example.com", "Race Customer")).get("id").asText());
        return UUID.fromString(post("/api/subscriptions",
                new SubscriptionCreateRequest(customerId, "race-plan-" + suffix)).get("id").asText());
    }

    private UUID pendingPlanId(UUID id) {
        return jdbcTemplate.queryForObject("SELECT pending_plan_id FROM subscription WHERE id = ?", UUID.class, id);
    }

    private Instant periodEnd(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT current_period_end FROM subscription WHERE id = ?", Timestamp.class, id).toInstant();
    }

    private String planCode(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT p.code FROM subscription s JOIN plan p ON p.id = s.plan_id WHERE s.id = ?", String.class, id);
    }

    /** A subscription on a monthly plan with a yearly one to move to, so a wrong period is visible. */
    private record PlanChangeFixture(UUID subscriptionId, String targetPlanCode, UUID targetPlanId) {
    }

    private PlanChangeFixture planChangeFixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String productCode = "race-pc-product-" + suffix;
        String fromPlan = "race-pc-from-" + suffix;
        String toPlan = "race-pc-to-" + suffix;

        post("/api/products", new ProductCreateRequest(productCode, "Race Plan Change Product", null));
        post("/api/plans", new PlanCreateRequest(productCode, fromPlan, "From", "MONTH", 1, 900L, "EUR", 0));
        post("/api/plans", new PlanCreateRequest(productCode, toPlan, "To", "YEAR", 1, 9900L, "EUR", 0));

        UUID customerId = UUID.fromString(post("/api/customers",
                new CustomerCreateRequest(null, "race-pc-" + suffix + "@example.com", "Race Plan Change Customer"))
                .get("id").asText());
        UUID subscriptionId = UUID.fromString(post("/api/subscriptions",
                new SubscriptionCreateRequest(customerId, fromPlan)).get("id").asText());
        UUID targetPlanId = jdbcTemplate.queryForObject(
                "SELECT id FROM plan WHERE tenant_id = ? AND code = ?", UUID.class, TENANT, toPlan);

        return new PlanChangeFixture(subscriptionId, toPlan, targetPlanId);
    }

    private UUID invoiceFor(UUID subscriptionId) {
        jdbcTemplate.update("UPDATE subscription SET current_period_end = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), subscriptionId);
        return UUID.fromString(post("/api/subscriptions/" + subscriptionId + "/invoices", null).get("id").asText());
    }

    private JsonNode post(String path, Object body) {
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                path, HttpMethod.POST, new HttpEntity<>(body, tenantHeaders(TENANT)), JsonNode.class);
        assertThat(response.getStatusCode()).as("POST %s", path).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }
}
