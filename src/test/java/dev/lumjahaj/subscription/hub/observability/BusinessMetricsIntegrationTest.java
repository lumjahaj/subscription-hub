package dev.lumjahaj.subscription.hub.observability;

import dev.lumjahaj.subscription.hub.audit.app.AuditService;
import dev.lumjahaj.subscription.hub.audit.domain.AuditEventType;
import dev.lumjahaj.subscription.hub.auth.api.dto.TokenRequest;
import dev.lumjahaj.subscription.hub.billing.app.BillingCycleJob;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import dev.lumjahaj.subscription.hub.testsupport.AbstractIntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application's own meters: that they exist under the names the
 * dashboards and alert rules query, and that business counters count only
 * what committed.
 *
 * Counters are read as before/after deltas, since the registry is shared by
 * every test in the context and other classes increment the same series.
 */
class BusinessMetricsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private AuditService audit;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private BillingCycleJob billingCycleJob;

    @Test
    void anAuditedChange_isCountedOnceItCommits_andNotAtAllIfItRollsBack() {
        double before = auditEvents("CUSTOMER_CREATED", "SYSTEM");

        TenantContext.runAs("acme", () -> transactionTemplate.executeWithoutResult(status -> {
            audit.recordSystem(AuditEventType.CUSTOMER_CREATED, UUID.randomUUID(), Map.of());
            status.setRollbackOnly();
        }));
        assertThat(auditEvents("CUSTOMER_CREATED", "SYSTEM"))
                .as("an event rolled back with its change is not a business event")
                .isEqualTo(before);

        TenantContext.runAs("acme", () -> transactionTemplate.executeWithoutResult(status ->
                audit.recordSystem(AuditEventType.CUSTOMER_CREATED, UUID.randomUUID(), Map.of())));
        assertThat(auditEvents("CUSTOMER_CREATED", "SYSTEM")).isEqualTo(before + 1);
    }

    @Test
    void aFailedLogin_isCountedWithoutNamingTheAccount() {
        double before = count("auth.login.failures", "principal", "tenant");

        ResponseEntity<String> response = restTemplate.exchange("/api/auth/token", HttpMethod.POST,
                new HttpEntity<>(new TokenRequest("acme", "admin@acme.test", "wrong-password")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(count("auth.login.failures", "principal", "tenant")).isEqualTo(before + 1);
        assertThat(registry.find("auth.login.failures").counters())
                .allSatisfy(counter -> assertThat(counter.getId().getTags())
                        .extracting(tag -> tag.getKey())
                        .containsOnly("principal", "application"));
    }

    @Test
    void aJobRun_recordsItsDurationAndLastSuccess() {
        billingCycleJob.run();

        assertThat(registry.find("jobs.run").tags("scheduled.job", "billing-cycle", "outcome", "success").timer())
                .isNotNull()
                .satisfies(timer -> assertThat(timer.count()).isPositive());
        assertThat(registry.find("jobs.last.success").tag("scheduled.job", "billing-cycle").gauge())
                .isNotNull()
                .satisfies(gauge -> assertThat(gauge.value()).isPositive());
    }

    @Test
    void theScrape_exposesTheApplicationMetricsUnderTheirPrometheusNames() {
        billingCycleJob.run();
        HttpHeaders scraper = new HttpHeaders();
        scraper.setBasicAuth("test-scraper", "test-only-scrape-password");

        String body = restTemplate.exchange("/actuator/prometheus", HttpMethod.GET,
                new HttpEntity<>(scraper), String.class).getBody();

        // The names docker/prometheus rules and the Grafana dashboard query.
        assertThat(body)
                .contains("jobs_run_seconds_count{")
                .contains("jobs_last_success_seconds{")
                .contains("notification_outbox_oldest_age_seconds{")
                .contains("status=\"PENDING\"")
                .contains("status=\"PUBLISHED\"")
                .contains("subscription_renewals_total{")
                .contains("notification_published_total{");
    }

    @Test
    void noMeter_usesALabelPrometheusReservesForTheScrapeTarget() {
        // Prometheus adds job and instance to every scraped series and, on a
        // clash, renames the application's label to exported_job - after which
        // an alert filtering on job="billing-cycle" matches nothing, silently.
        // JobMetrics once tagged its meters "job"; only a live scrape showed it.
        billingCycleJob.run();

        assertThat(registry.getMeters())
                .flatExtracting(meter -> meter.getId().getTags())
                .extracting(tag -> tag.getKey())
                .doesNotContain("job", "instance");
    }

    private double auditEvents(String type, String actor) {
        return registry.find("audit.events").tags("type", type, "actor", actor).counters().stream()
                .mapToDouble(Counter::count).sum();
    }

    private double count(String name, String tagKey, String tagValue) {
        return registry.find(name).tag(tagKey, tagValue).counters().stream()
                .mapToDouble(Counter::count).sum();
    }
}
