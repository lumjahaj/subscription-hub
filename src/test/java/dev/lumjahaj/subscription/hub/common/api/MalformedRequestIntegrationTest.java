package dev.lumjahaj.subscription.hub.common.api;

import dev.lumjahaj.subscription.hub.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requests Spring MVC rejects on its own, before any controller runs: a body
 * that is not JSON, the wrong method, the wrong content type, a URL with no
 * handler. Each is the caller's mistake, so each must be a 4xx problem+json
 * with a code, not the 500 INTERNAL_ERROR the catch-all handler used to turn
 * them into - which also counted them as server errors.
 *
 * Through the real request path for the same reason as
 * InvalidRequestParameterIntegrationTest: these exceptions are raised by the
 * dispatcher, so a controller-level test would never see them.
 */
class MalformedRequestIntegrationTest extends AbstractIntegrationTest {

    @Test
    void bodyThatIsNotJson_isBadRequest() {
        ResponseEntity<String> response = postJson("/api/products", "{\"code\": \"broken\", ");

        assertProblem(response, HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST_BODY");
    }

    @Test
    void fieldOfTheWrongType_namesTheFieldButNotTheValueOrTheClass() {
        // A marker that cannot occur in the response by accident, as in
        // InvalidRequestParameterIntegrationTest.
        ResponseEntity<String> response = postJson("/api/plans", """
                {"code": "p", "productCode": "x", "name": "n", "intervalUnit": "MONTH",
                 "intervalCount": 1, "amountCents": "echo-marker-7f3a", "currency": "EUR", "trialDays": 0}
                """);

        assertProblem(response, HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST_BODY");
        assertThat(response.getBody())
                .contains("amountCents")
                .doesNotContain("echo-marker-7f3a")
                .doesNotContain("PlanCreateRequest");
    }

    @Test
    void unsupportedHttpMethod_isMethodNotAllowedWithAnAllowHeader() {
        ResponseEntity<String> response = restTemplate.exchange("/api/products", HttpMethod.DELETE,
                new HttpEntity<>(tenantHeaders("acme")), String.class);

        assertProblem(response, HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED");
        assertThat(response.getHeaders().getAllow()).contains(HttpMethod.GET, HttpMethod.POST);
    }

    @Test
    void unsupportedContentType_isUnsupportedMediaType() {
        HttpHeaders headers = tenantHeaders("acme");
        headers.setContentType(MediaType.TEXT_PLAIN);

        ResponseEntity<String> response = restTemplate.exchange("/api/products", HttpMethod.POST,
                new HttpEntity<>("code=x", headers), String.class);

        assertProblem(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE");
        // The detail must not name a single media type the Accept header then
        // contradicts; the header is the authoritative list.
        assertThat(response.getHeaders().getAccept()).contains(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).doesNotContain("must be application/json");
    }

    @Test
    void urlWithNoHandler_isNotFound() {
        ResponseEntity<String> response = restTemplate.exchange("/api/no-such-endpoint", HttpMethod.GET,
                new HttpEntity<>(tenantHeaders("acme")), String.class);

        assertProblem(response, HttpStatus.NOT_FOUND, "ENDPOINT_NOT_FOUND");
    }

    @Test
    void urlWithNoHandler_withoutAToken_isStillUnauthorized() {
        // Authentication runs before dispatch, so an anonymous caller cannot
        // use 404 versus 401 to map which endpoints exist.
        ResponseEntity<String> response = restTemplate.exchange("/api/no-such-endpoint", HttpMethod.GET,
                HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<String> postJson(String path, String body) {
        HttpHeaders headers = tenantHeaders("acme");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static void assertProblem(ResponseEntity<String> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .satisfies(type -> assertThat(type.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue());
        assertThat(response.getBody()).contains("\"code\":\"" + code + "\"").contains("requestId");
    }
}
