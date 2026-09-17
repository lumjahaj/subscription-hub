package dev.lumjahaj.subscription.hub.observability;

import dev.lumjahaj.subscription.hub.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may read metrics, asserted in both directions: the scrape account can,
 * and nothing else can - including a platform administrator, whose token
 * opens every other platform endpoint.
 */
class MetricsEndpointIntegrationTest extends AbstractIntegrationTest {

    private static final String PROMETHEUS = "/actuator/prometheus";

    @Test
    void scrapeAccount_readsPrometheusMetrics() {
        ResponseEntity<String> response = scrape(scraperHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/plain");
        // Built-in meters the dashboards and the open-in-view work rely on,
        // tagged with the application name.
        assertThat(response.getBody())
                .contains("http_server_requests_seconds")
                .contains("hikaricp_connections_active")
                .contains("application=\"subscription-hub\"");
    }

    @Test
    void noCredentials_isUnauthorized() {
        ResponseEntity<String> response = scrape(new HttpHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("UNAUTHENTICATED").doesNotContain("jvm_");
    }

    @Test
    void wrongPassword_isUnauthorized() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth("test-scraper", "not-the-scrape-password");

        assertThat(scrape(headers).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aTenantAdminToken_isUnauthorized() {
        assertThat(scrape(tenantHeaders("acme")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aPlatformAdminToken_isUnauthorized() {
        // The metrics chain does not read bearer tokens at all, so the platform
        // principal is simply anonymous there.
        assertThat(scrape(platformHeaders()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void theScrapeAccount_opensNothingElse() {
        // Basic auth exists only on the metrics chain, so the same credential
        // on a tenant endpoint is an anonymous request.
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/products", HttpMethod.GET, new HttpEntity<>(scraperHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void otherActuatorEndpoints_belongToThePlatformAdmin() {
        assertThat(get("/actuator/info", tenantHeaders("acme")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/actuator/info", platformHeaders()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void healthProbes_arePublic() {
        assertThat(get("/actuator/health/liveness", new HttpHeaders()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/actuator/health/readiness", new HttpHeaders()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<String> scrape(HttpHeaders headers) {
        return get(PROMETHEUS, headers);
    }

    private ResponseEntity<String> get(String path, HttpHeaders headers) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private static HttpHeaders scraperHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth("test-scraper", "test-only-scrape-password");
        return headers;
    }
}
