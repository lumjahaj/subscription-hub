package dev.lumjahaj.subscription.hub.testsupport;

import dev.lumjahaj.subscription.hub.tenancy.api.TenantResolverFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;

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
@TestPropertySource(properties = "billing.cycle.cron=-")
public abstract class AbstractIntegrationTest {

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

    protected static HttpHeaders tenantHeaders(String tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(TenantResolverFilter.TENANT_HEADER, tenantId);
        return headers;
    }
}
