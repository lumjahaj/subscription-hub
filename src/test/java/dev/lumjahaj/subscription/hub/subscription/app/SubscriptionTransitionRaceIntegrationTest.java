package dev.lumjahaj.subscription.hub.subscription.app;

import com.fasterxml.jackson.databind.JsonNode;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductCreateRequest;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerCreateRequest;
import dev.lumjahaj.subscription.hub.dunning.app.DunningService;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionCreateRequest;
import dev.lumjahaj.subscription.hub.subscription.domain.SubscriptionRepository;
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
