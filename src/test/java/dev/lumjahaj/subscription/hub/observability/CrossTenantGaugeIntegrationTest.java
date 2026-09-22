package dev.lumjahaj.subscription.hub.observability;

import dev.lumjahaj.subscription.hub.notification.domain.NotificationRepository;
import dev.lumjahaj.subscription.hub.notification.domain.NotificationStatus;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import dev.lumjahaj.subscription.hub.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two gauges that are cross-tenant on purpose still see across tenants.
 *
 * <p>Both run during a Prometheus scrape, which is not a request and has no
 * tenant, and both aggregate over every active tenant. Row-level security
 * would otherwise reduce them to 0 — not an error, not a failure, just a
 * number that is quietly wrong, which for a metric is the worst outcome
 * available. They reach past the policies through SECURITY DEFINER functions
 * (V21), and this is what proves the reach works.
 *
 * <p><b>Why this cannot be left to BusinessMetricsIntegrationTest.</b> That
 * test asserts the metric <i>names</i> appear in a scrape, which they would
 * whatever the value was: a gauge reading 0 still prints its name. Only a
 * non-zero assertion, against a row planted under a tenant that is not in
 * context, can tell the difference — the same shape of gap that let a meter
 * tagged "job" pass a green suite and match nothing in Prometheus.
 */
class CrossTenantGaugeIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private NotificationRepository notifications;

    /** Runs as the owner, so it can plant a row for a tenant nobody is. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String dedupKey = "cross-tenant-gauge:" + UUID.randomUUID();

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        jdbcTemplate.update("DELETE FROM notification WHERE dedup_key = ?", dedupKey);
    }

    @Test
    void theOutboxGaugeReadsAcrossTenantsWithNoTenantInContext() {
        // Planted under acme, deliberately aged, and never touched through a
        // request - so the only way to see it is the cross-tenant path.
        jdbcTemplate.update("""
                INSERT INTO notification
                    (tenant_id, type, dedup_key, recipient, subject, html_body, text_body,
                     status, created_at, updated_at)
                VALUES ('acme', 'INVOICE_ISSUED', ?, 'someone@acme.test', 'subject',
                        '<p>html</p>', 'text', 'PENDING', now() - interval '600 seconds', now())
                """, dedupKey);

        // A scrape has no tenant. This is exactly the state the gauge is read
        // in, and exactly the state the policies refuse every ordinary query.
        TenantContext.clear();

        assertThat(notifications.oldestAgeSecondsAcrossActiveTenants(NotificationStatus.PENDING))
                .as("the outbox gauge must see a pending row belonging to a tenant nobody is")
                .isGreaterThanOrEqualTo(600d);
    }

    /**
     * The two properties that make a SECURITY DEFINER function safe rather
     * than a privilege-escalation hole. Neither is visible in behaviour, so
     * without this nothing fails if a later migration recreates a function
     * and drops one of them.
     *
     * <p>A pinned search_path is the important one: without it the caller
     * decides where {@code notification} and {@code tenant} resolve, and can
     * point them at objects of their own that then execute as the table owner.
     */
    @Test
    void theBypassFunctionsAreOwnerScopedAndNotReachableByEveryone() {
        for (String function : new String[] {
                "notification_outbox_oldest_age_seconds(text)",
                "payment_oldest_pending_age_seconds()"}) {

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT prosecdef FROM pg_proc WHERE oid = ?::regprocedure", Boolean.class, function))
                    .as("%s must run as its owner, or it cannot see past the policies", function)
                    .isTrue();

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT proconfig FROM pg_proc WHERE oid = ?::regprocedure", String.class, function))
                    .as("%s must pin its search_path, or its caller chooses what it executes", function)
                    .contains("search_path=");

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT has_function_privilege('public', ?, 'EXECUTE')", Boolean.class, function))
                    .as("%s must not be executable by everyone who can connect", function)
                    .isFalse();

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT has_function_privilege(?, ?, 'EXECUTE')", Boolean.class, APP_ROLE, function))
                    .as("%s must still be callable by the application", function)
                    .isTrue();
        }
    }
}
