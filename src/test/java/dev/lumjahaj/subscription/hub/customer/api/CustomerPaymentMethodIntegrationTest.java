package dev.lumjahaj.subscription.hub.customer.api;

import dev.lumjahaj.subscription.hub.auth.api.dto.TokenRequest;
import dev.lumjahaj.subscription.hub.auth.api.dto.TokenResponse;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerCreateRequest;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerResponse;
import dev.lumjahaj.subscription.hub.customer.api.dto.PaymentMethodRequest;
import dev.lumjahaj.subscription.hub.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stored payment method is what lets dunning charge a customer with
 * nobody present, so the interesting assertions are that it is really
 * persisted, that it is tenant-scoped, and that the token never leaves the
 * application in a response body.
 */
class CustomerPaymentMethodIntegrationTest extends AbstractIntegrationTest {

    private static final String TENANT = "acme";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void setPaymentMethod_storesTheTokenAndReportsItWithoutEchoingIt() {
        UUID customerId = createCustomer(TENANT);

        ResponseEntity<String> response = setPaymentMethod(customerId, "pm_card_visa", TENANT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"hasDefaultPaymentMethod\":true");
        assertThat(response.getBody()).doesNotContain("pm_card_visa");
        assertThat(storedPaymentMethod(customerId)).isEqualTo("pm_card_visa");
    }

    @Test
    void setPaymentMethod_twice_replacesTheToken() {
        UUID customerId = createCustomer(TENANT);
        setPaymentMethod(customerId, "pm_card_visa", TENANT);

        setPaymentMethod(customerId, "pm_card_mastercard", TENANT);

        assertThat(storedPaymentMethod(customerId)).isEqualTo("pm_card_mastercard");
    }

    @Test
    void clearPaymentMethod_removesTheTokenAndIsIdempotent() {
        UUID customerId = createCustomer(TENANT);
        setPaymentMethod(customerId, "pm_card_visa", TENANT);

        assertThat(clearPaymentMethod(customerId, TENANT).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(storedPaymentMethod(customerId)).isNull();

        // Clearing a field, not deleting a row: the second call is a no-op,
        // not a 404.
        ResponseEntity<String> again = clearPaymentMethod(customerId, TENANT);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody()).contains("\"hasDefaultPaymentMethod\":false");
    }

    @Test
    void getCustomer_reportsWhetherAMethodIsStored() {
        UUID customerId = createCustomer(TENANT);

        ResponseEntity<CustomerResponse> before = restTemplate.exchange(
                "/api/customers/" + customerId, HttpMethod.GET,
                new HttpEntity<>(tenantHeaders(TENANT)), CustomerResponse.class);
        assertThat(before.getBody().hasDefaultPaymentMethod()).isFalse();

        setPaymentMethod(customerId, "pm_card_visa", TENANT);

        ResponseEntity<CustomerResponse> after = restTemplate.exchange(
                "/api/customers/" + customerId, HttpMethod.GET,
                new HttpEntity<>(tenantHeaders(TENANT)), CustomerResponse.class);
        assertThat(after.getBody().hasDefaultPaymentMethod()).isTrue();
    }

    @Test
    void setPaymentMethod_withABlankToken_returnsBadRequest() {
        UUID customerId = createCustomer(TENANT);

        ResponseEntity<String> response = setPaymentMethod(customerId, " ", TENANT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("VALIDATION_ERROR");
        assertThat(storedPaymentMethod(customerId)).isNull();
    }

    @Test
    void setPaymentMethod_underAnotherTenant_returnsNotFound() {
        UUID customerId = createCustomer(TENANT);

        ResponseEntity<String> response = setPaymentMethod(customerId, "pm_card_visa", "demo");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("CUSTOMER_NOT_FOUND");
        assertThat(storedPaymentMethod(customerId)).isNull();
    }

    @Test
    void setPaymentMethod_asSupport_isForbidden() {
        UUID customerId = createCustomer(TENANT);
        HttpHeaders headers = supportHeaders();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/customers/" + customerId + "/payment-method", HttpMethod.PUT,
                new HttpEntity<>(new PaymentMethodRequest("pm_card_visa"), headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(storedPaymentMethod(customerId)).isNull();
    }

    // ---- helpers ----

    private ResponseEntity<String> setPaymentMethod(UUID customerId, String token, String tenant) {
        return restTemplate.exchange(
                "/api/customers/" + customerId + "/payment-method", HttpMethod.PUT,
                new HttpEntity<>(new PaymentMethodRequest(token), tenantHeaders(tenant)), String.class);
    }

    private ResponseEntity<String> clearPaymentMethod(UUID customerId, String tenant) {
        return restTemplate.exchange(
                "/api/customers/" + customerId + "/payment-method", HttpMethod.DELETE,
                new HttpEntity<>(tenantHeaders(tenant)), String.class);
    }

    private String storedPaymentMethod(UUID customerId) {
        return jdbcTemplate.queryForObject(
                "SELECT default_payment_method FROM customer WHERE id = ?", String.class, customerId);
    }

    private UUID createCustomer(String tenant) {
        var request = new CustomerCreateRequest(
                null, "pm-" + UUID.randomUUID() + "@example.com", "Payment Method Customer");
        ResponseEntity<CustomerResponse> response = restTemplate.exchange(
                "/api/customers", HttpMethod.POST,
                new HttpEntity<>(request, tenantHeaders(tenant)), CustomerResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private HttpHeaders supportHeaders() {
        ResponseEntity<TokenResponse> login = restTemplate.exchange(
                "/api/auth/token", HttpMethod.POST,
                new HttpEntity<>(new TokenRequest(TENANT, "support@acme.test", "subscriptionhub")),
                TokenResponse.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(login.getBody().token());
        return headers;
    }
}
