package dev.lumjahaj.subscription.hub.common.api;

import dev.lumjahaj.subscription.hub.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A request parameter that cannot be converted to its declared type is the
 * caller's mistake, so it must be a 400, not the 500 the catch-all handler
 * would report. Spring rejects these before any controller code runs, so the
 * real request path is the only honest place to test them.
 */
class InvalidRequestParameterIntegrationTest extends AbstractIntegrationTest {

    @Test
    void unknownEnumQueryParameter_isBadRequestListingTheAllowedValues() {
        ResponseEntity<String> response = get("/api/audit-events?entityType=NOPE&entityId=x");

        assertBadRequest(response);
        assertThat(response.getBody()).contains("entityType must be one of").contains("SUBSCRIPTION");
    }

    @Test
    void malformedUuidQueryParameter_isBadRequest() {
        ResponseEntity<String> response = get("/api/subscriptions?customerId=not-a-uuid");

        assertBadRequest(response);
        assertThat(response.getBody()).contains("customerId must be a UUID");
    }

    @Test
    void malformedUuidPathVariable_isBadRequest() {
        ResponseEntity<String> response = get("/api/subscriptions/not-a-uuid");

        assertBadRequest(response);
        assertThat(response.getBody()).contains("id must be a UUID");
    }

    @Test
    void rejectedValue_isNotEchoedBack() {
        // Reflecting raw input into a response is how a harmless error page
        // becomes a vector; the parameter name and the expected shape are
        // enough for the caller to fix the request.
        // A marker that cannot occur in the response by accident (an earlier
        // version checked for "script", which "/api/subscriptions" contains).
        ResponseEntity<String> response = get("/api/subscriptions?customerId=<b>echo-marker-7f3a</b>");

        assertBadRequest(response);
        assertThat(response.getBody()).doesNotContain("echo-marker-7f3a");
    }

    @Test
    void unknownSortProperty_doesNotNameTheEntityClass() {
        ResponseEntity<String> response = get("/api/products?sort=noSuchProperty");

        assertBadRequest(response);
        assertThat(response.getBody()).doesNotContain("ProductEntity").doesNotContain("noSuchProperty");
    }

    private ResponseEntity<String> get(String path) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(tenantHeaders("acme")), String.class);
    }

    private static void assertBadRequest(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("VALIDATION_ERROR");
    }
}
