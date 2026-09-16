package dev.lumjahaj.subscription.hub.testsupport;

import dev.lumjahaj.subscription.hub.auth.api.dto.TokenRequest;
import dev.lumjahaj.subscription.hub.auth.api.dto.TokenResponse;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base for integration tests that need a real Postgres and a real HTTP
 * request path (RANDOM_PORT + TestRestTemplate, not MockMvc) so requests
 * actually pass through TenantResolverFilter and Hibernate's @TenantId,
 * not just the controller layer.
 *
 * Both containers are static fields on this shared base class so every
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
 * "Container is started (JDBC URL", which is a JDBC-specific log line. MinIO
 * never emits it, so that check still counts Postgres containers only and
 * still reads 1 — a second, non-JDBC container does not weaken it.
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
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// The seeded logins these tests authenticate as live in db/seed, which is
// only on the Flyway path under this profile. On the shared base, never a
// subclass: a differing profile set is a different context-cache key.
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "billing.cycle.cron=-",
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
        "stripe.webhook-secret=whsec_test_only_webhook_signing_secret"
})
public abstract class AbstractIntegrationTest {

    /** Matches the bcrypt hash in db/seed/V9001__seed_dev_users.sql. */
    private static final String SEEDED_PASSWORD = "subscriptionhub";

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    // No @ServiceConnection equivalent: Spring Boot has no S3 auto-configuration
    // to feed connection details into (that lives in Spring Cloud AWS), so the
    // endpoint and credentials are wired through @DynamicPropertySource instead.
    static final MinIOContainer MINIO =
            new MinIOContainer("minio/minio:RELEASE.2024-08-17T01-24-54Z");

    static {
        POSTGRES.start();
        MINIO.start();
    }

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("billing.pdf.endpoint", MINIO::getS3URL);
        registry.add("billing.pdf.access-key", MINIO::getUserName);
        registry.add("billing.pdf.secret-key", MINIO::getPassword);
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
