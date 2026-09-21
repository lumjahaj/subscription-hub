package dev.lumjahaj.subscription.hub.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import dev.lumjahaj.subscription.hub.auth.api.dto.PlatformTokenRequest;
import dev.lumjahaj.subscription.hub.auth.api.dto.PlatformTokenResponse;
import dev.lumjahaj.subscription.hub.auth.api.dto.TokenRequest;
import dev.lumjahaj.subscription.hub.auth.api.dto.TokenResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base for integration tests that need a real Postgres and a real HTTP
 * request path (RANDOM_PORT + TestRestTemplate, not MockMvc) so requests
 * actually pass through TenantResolverFilter and Hibernate's @TenantId,
 * not just the controller layer.
 *
 * All four containers are static fields on this shared base class so every
 * subclass reuses the same instances and the same cached Spring context
 * instead of paying startup cost per test class.
 *
 * Deliberately NOT annotated @Testcontainers/@Container: that extension has a
 * per-test-class lifecycle, so it stops the container after every subclass
 * while Spring's context cache keeps handing out the DataSource built from the
 * first container's JDBC URL. The second integration test class to run then
 * talks to a dead port. Starting them here in a static initializer instead gives
 * them JVM-wide lifetime; Ryuk reaps them on exit.
 *
 * The "exactly one container per build" invariant in CLAUDE.md §5 greps for
 * "Container is started (JDBC URL", which is a JDBC-specific log line. MinIO,
 * Mailpit and ElasticMQ never emit it, so that check still counts Postgres
 * containers only and still reads 1 — three more non-JDBC containers don't
 * weaken it.
 *
 * Property overrides live here on the shared base rather than per-subclass so
 * every subclass shares one context-cache key; a @TestPropertySource or
 * @DynamicPropertySource on a subclass would fork the cache and quietly build
 * a second context (and, before long, a second set of containers):
 * - billing.cycle.cron is disabled ("-" is Spring's disabled-cron sentinel) so
 *   BillingCycleJob's hourly schedule can't fire mid-test and invoice or renew
 *   a subscription a test is in the middle of asserting against.
 * - billing.pdf.* points at the MinIO container. Without this the S3Client
 *   bean fails to build at all, since application.yml resolves its credentials
 *   from MINIO_ROOT_USER/PASSWORD env vars that don't exist in a test JVM.
 * - notification.relay.delay is stretched to a day: NotificationRelayJob would
 *   otherwise fire on its own schedule mid-test. Notification tests drive
 *   NotificationRelayService directly for deterministic timing, the same
 *   reason billing.cycle.cron and dunning.cycle.cron are disabled above.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// The seeded logins these tests authenticate as live in db/seed, which is
// only on the Flyway path under this profile. On the shared base, never a
// subclass: a differing profile set is a different context-cache key.
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "billing.cycle.cron=-",
        // Same reason as billing.cycle.cron: DunningJob is invoked directly
        // by its test, and a scheduled run firing mid-assertion would charge
        // an invoice another test is in the middle of checking.
        "dunning.cycle.cron=-",
        // And again for PaymentReconciliationJob, which would otherwise
        // settle the deliberately-stuck PENDING payments other tests leave
        // behind - DunningIntegrationTest parks one on purpose.
        "payment.reconciliation.cron=-",
        // JwtConfig resolves this eagerly and rejects anything under 32
        // bytes, so without it every context fails to build - the same
        // trap billing.pdf.* hit. A fixed test secret also keeps tokens
        // reproducible across a run.
        "auth.jwt.secret=test-only-jwt-secret-at-least-32-bytes-long",
        // Webhook verification needs only this secret — no API key, no
        // network — so StripeWebhookIntegrationTest signs its own payloads
        // and runs in this same context, with payment.provider still the
        // fake. Here on the shared base, never a subclass: a differing
        // property set is a different context-cache key.
        "stripe.webhook-secret=whsec_test_only_webhook_signing_secret",
        "notification.relay.delay=86400000",
        // The scrape account MetricsEndpointIntegrationTest reads
        // /actuator/prometheus with. Unset, every scrape is 401 by design.
        "metrics.scrape.username=test-scraper",
        "metrics.scrape.password=test-only-scrape-password"
})
// @SpringBootTest replaces every metrics exporter with a SimpleMeterRegistry
// unless told otherwise, so without this /actuator/prometheus would not exist
// in tests at all. On the base for the usual reason: an annotation on one
// subclass would be a second context.
@AutoConfigureObservability
public abstract class AbstractIntegrationTest {

    /** Matches the bcrypt hash in db/seed/V9001__seed_dev_users.sql. */
    private static final String SEEDED_PASSWORD = "subscriptionhub";

    /**
     * The password for the non-superuser role the application connects as.
     * Only ever used against a throwaway container, so it is a literal here
     * rather than another thing a contributor has to set up.
     */
    protected static final String APP_ROLE_PASSWORD = "test-app-role";

    // Mounted the same way docker-compose.yml mounts it, from the same file,
    // so the role the tests run against is created by exactly the script a
    // developer's database uses. The image runs everything in this directory
    // when the data directory is first initialised, which for a fresh
    // container is always.
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withEnv("POSTGRES_APP_PASSWORD", APP_ROLE_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forHostPath("docker/postgres/init/01-app-role.sh", 0755),
                    "/docker-entrypoint-initdb.d/01-app-role.sh");

    // No @ServiceConnection equivalent: Spring Boot has no S3 auto-configuration
    // to feed connection details into (that lives in Spring Cloud AWS), so the
    // endpoint and credentials are wired through @DynamicPropertySource instead.
    // quay.io, not Docker Hub: MinIO's docker.io/minio/minio repository is
    // gone (a pull now answers "repository does not exist"), which broke CI
    // while every developer machine kept passing from its local image cache.
    // MinIO's own documentation has pointed at quay.io for some time.
    // asCompatibleSubstituteFor tells Testcontainers this is still MinIO;
    // MinIOContainer otherwise rejects an image whose name isn't minio/minio.
    static final MinIOContainer MINIO = new MinIOContainer(
            DockerImageName.parse("quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z")
                    .asCompatibleSubstituteFor("minio/minio"));

    // Local SMTP inbox for notification emails. No Testcontainers module of
    // its own, so a plain GenericContainer with its two ports exposed; its
    // REST API (read by MailpitTestClient) is what tests assert against.
    static final GenericContainer<?> MAILPIT =
            new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.31.1"))
                    .withExposedPorts(1025, 8025);

    // Local SQS. The queue is defined in docker/elasticmq/elasticmq.conf and
    // mounted here from the same file docker-compose.yml uses for local dev -
    // one source of truth for the queue and its dead-letter redrive policy.
    static final GenericContainer<?> ELASTICMQ =
            new GenericContainer<>(DockerImageName.parse("softwaremill/elasticmq-native:1.6.16"))
                    .withExposedPorts(9324)
                    .withCopyFileToContainer(
                            MountableFile.forHostPath("docker/elasticmq/elasticmq.conf"), "/opt/elasticmq.conf");

    static {
        POSTGRES.start();
        MINIO.start();
        MAILPIT.start();
        ELASTICMQ.start();
    }

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("billing.pdf.endpoint", MINIO::getS3URL);
        registry.add("billing.pdf.access-key", MINIO::getUserName);
        registry.add("billing.pdf.secret-key", MINIO::getPassword);
        registry.add("spring.mail.host", MAILPIT::getHost);
        registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
        registry.add("spring.cloud.aws.sqs.endpoint",
                () -> "http://" + ELASTICMQ.getHost() + ":" + ELASTICMQ.getMappedPort(9324));
    }

    /** The Mailpit container's REST API base URL, for reading delivered mail. */
    protected static String mailpitApiUrl() {
        return "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025);
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    /**
     * Tokens are cached across every test class in the run. They are
     * immutable, valid for an hour, and identical for a given tenant, so
     * re-issuing one per call would add a bcrypt verification (~100ms by
     * design) to all 59 call sites for nothing.
     */
    private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();

    /**
     * Same name and signature it had when the tenant travelled in an
     * X-Tenant-Id header — only the implementation changed, which is why
     * no test class needed editing when authentication landed.
     *
     * What the tests assert got stronger for free: tenantHeaders("demo")
     * against acme's data still expects a 404, but now because the caller
     * is genuinely authenticated as demo, not because it claimed to be.
     *
     * No longer static, since issuing a token needs restTemplate. Every
     * caller is an instance method, so that change was invisible too.
     */
    protected HttpHeaders tenantHeaders(String tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TOKENS.computeIfAbsent(tenantId, this::login));
        return headers;
    }

    /**
     * A token for the seeded platform administrator (db/seed V9002), which
     * carries no tenant_id. Cached under a key no tenant slug can take —
     * slugs cannot contain a colon — so it can never be handed out as a
     * tenant's token.
     */
    protected HttpHeaders platformHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TOKENS.computeIfAbsent("platform:admin", key -> platformLogin()));
        return headers;
    }

    /**
     * One record's audit history as the tenant's admin sees it, newest first.
     * The status is asserted here so a failing read shows up as that, not as a
     * missing event.
     */
    protected JsonNode auditHistory(String tenantId, String entityType, Object entityId) {
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/audit-events?entityType=" + entityType + "&entityId=" + entityId, HttpMethod.GET,
                new HttpEntity<>(tenantHeaders(tenantId)), JsonNode.class);
        assertThat(response.getStatusCode()).as("audit history of %s %s", entityType, entityId)
                .isEqualTo(HttpStatus.OK);
        return response.getBody().get("content");
    }

    private String platformLogin() {
        ResponseEntity<PlatformTokenResponse> response = restTemplate.exchange(
                "/api/platform/auth/token", HttpMethod.POST,
                new HttpEntity<>(new PlatformTokenRequest("platform@subscriptionhub.test", SEEDED_PASSWORD)),
                PlatformTokenResponse.class);

        assertThat(response.getStatusCode())
                .as("login for the seeded platform administrator")
                .isEqualTo(HttpStatus.OK);
        return response.getBody().token();
    }

    /**
     * Logs in as the tenant's seeded admin over real HTTP rather than
     * minting a token with the signing key directly. A token this suite
     * fabricated could diverge from one AuthService actually issues — a
     * missing claim would then be invisible here and fail in production.
     */
    private String login(String tenantId) {
        ResponseEntity<TokenResponse> response = restTemplate.exchange(
                "/api/auth/token", HttpMethod.POST,
                new HttpEntity<>(new TokenRequest(tenantId, "admin@" + tenantId + ".test", SEEDED_PASSWORD)),
                TokenResponse.class);

        // Asserted before use: a failed login otherwise surfaces as a
        // NullPointerException in whichever unrelated test happened to ask
        // for the token first.
        assertThat(response.getStatusCode())
                .as("login for seeded admin of tenant '%s'", tenantId)
                .isEqualTo(HttpStatus.OK);
        return response.getBody().token();
    }
}
