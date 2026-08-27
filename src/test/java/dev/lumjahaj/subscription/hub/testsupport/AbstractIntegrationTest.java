package dev.lumjahaj.subscription.hub.testsupport;

import dev.lumjahaj.subscription.hub.tenancy.api.TenantResolverFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for integration tests that need a real Postgres and a real HTTP
 * request path (RANDOM_PORT + TestRestTemplate, not MockMvc) so requests
 * actually pass through TenantResolverFilter and Hibernate's @TenantId,
 * not just the controller layer.
 *
 * The container is a static field on this shared base class so every
 * subclass reuses the same instance and the same cached Spring context
 * instead of paying startup cost per test class.
 *
 * Deliberately NOT annotated @Testcontainers/@Container: that extension has a
 * per-test-class lifecycle, so it stops the container after every subclass
 * while Spring's context cache keeps handing out the DataSource built from the
 * first container's JDBC URL. The second integration test class to run then
 * talks to a dead port. Starting it here in a static initializer instead gives
 * it JVM-wide lifetime; Ryuk reaps it on exit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    protected static HttpHeaders tenantHeaders(String tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(TenantResolverFilter.TENANT_HEADER, tenantId);
        return headers;
    }
}
