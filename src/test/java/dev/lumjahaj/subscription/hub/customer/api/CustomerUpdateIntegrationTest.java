package dev.lumjahaj.subscription.hub.customer.api;

import com.fasterxml.jackson.databind.JsonNode;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerCreateRequest;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerResponse;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerUpdateRequest;
import dev.lumjahaj.subscription.hub.customer.api.dto.PaymentMethodRequest;
import dev.lumjahaj.subscription.hub.customer.domain.CustomerRepository;
import dev.lumjahaj.subscription.hub.customer.infra.jpa.CustomerEntity;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import dev.lumjahaj.subscription.hub.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The first update endpoint, and the proof that it cannot lose an update:
 * an edit must say which version it was based on, and an edit based on a
 * version that is no longer current is refused rather than applied.
 */
class CustomerUpdateIntegrationTest extends AbstractIntegrationTest {

    private static final String TENANT = "acme";

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void update_withTheCurrentETag_appliesTheChangeAndReturnsANewETag() {
        Created customer = createCustomer();

        ResponseEntity<CustomerResponse> response = update(customer.id(), customer.etag(),
                new CustomerUpdateRequest("crm-42", customer.email(), "Renamed Customer"), CustomerResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().name()).isEqualTo("Renamed Customer");
        assertThat(response.getBody().externalId()).isEqualTo("crm-42");
        assertThat(response.getHeaders().getETag()).isNotNull().isNotEqualTo(customer.etag());
        // The ETag a PUT returns is the one a fresh read returns, so it can be
        // used for the next edit without reading again.
        assertThat(get(customer.id()).getHeaders().getETag()).isEqualTo(response.getHeaders().getETag());
    }

    @Test
    void update_withoutIfMatch_isPreconditionRequired_andChangesNothing() {
        Created customer = createCustomer();

        ResponseEntity<String> response = update(customer.id(), null,
                new CustomerUpdateRequest(null, customer.email(), "Blind Overwrite"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
        assertThat(response.getBody()).contains("PRECONDITION_REQUIRED");
        assertThat(get(customer.id()).getBody().name()).isEqualTo("Original Name");
    }

    @Test
    void update_withAWildcardIfMatch_isTreatedAsMissing() {
        // RFC 9110 lets "*" mean any version, which is an unconditional
        // overwrite by another name.
        Created customer = createCustomer();

        ResponseEntity<String> response = update(customer.id(), "*",
                new CustomerUpdateRequest(null, customer.email(), "Wildcard"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
    }

    @Test
    void update_withAStaleETag_isPreconditionFailed_andChangesNothing() {
        Created customer = createCustomer();
        update(customer.id(), customer.etag(),
                new CustomerUpdateRequest(null, customer.email(), "First Edit"), CustomerResponse.class);

        // A second client still holding the original ETag.
        ResponseEntity<String> response = update(customer.id(), customer.etag(),
                new CustomerUpdateRequest(null, customer.email(), "Lost Update"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
        assertThat(response.getBody()).contains("PRECONDITION_FAILED");
        assertThat(get(customer.id()).getBody().name()).isEqualTo("First Edit");
    }

    @Test
    void settingAPaymentMethod_invalidatesAnEarlierETag() {
        // The representation changed (hasDefaultPaymentMethod), so an edit
        // based on the earlier read is stale even though it touches other fields.
        Created customer = createCustomer();
        ResponseEntity<String> paymentMethod = restTemplate.exchange(
                "/api/customers/" + customer.id() + "/payment-method", HttpMethod.PUT,
                new HttpEntity<>(new PaymentMethodRequest("pm_card_visa"), tenantHeaders(TENANT)), String.class);
        assertThat(paymentMethod.getHeaders().getETag()).isNotEqualTo(customer.etag());

        ResponseEntity<String> response = update(customer.id(), customer.etag(),
                new CustomerUpdateRequest(null, customer.email(), "Stale"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
    }

    @Test
    void update_toAnEmailAnotherCustomerHas_isAConflict() {
        Created taken = createCustomer();
        Created customer = createCustomer();

        ResponseEntity<String> response = update(customer.id(), customer.etag(),
                new CustomerUpdateRequest(null, taken.email(), "Original Name"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("CUSTOMER_ALREADY_EXISTS");
    }

    @Test
    void update_underAnotherTenant_isNotFound() {
        Created customer = createCustomer();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/customers/" + customer.id(), HttpMethod.PUT,
                new HttpEntity<>(new CustomerUpdateRequest(null, customer.email(), "Cross Tenant"),
                        ifMatch(tenantHeaders("demo"), customer.etag())),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(customer.id()).getBody().name()).isEqualTo("Original Name");
    }

    @Test
    void update_withInvalidFields_isBadRequest() {
        Created customer = createCustomer();

        ResponseEntity<String> response = update(customer.id(), customer.etag(),
                new CustomerUpdateRequest(null, "not-an-email", " "), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("VALIDATION_ERROR");
    }

    @Test
    void update_isAuditedWithFieldNamesButNoValues_andANoOpIsNeither() {
        Created customer = createCustomer();
        String newEmail = "renamed-" + UUID.randomUUID() + "@example.com";

        ResponseEntity<CustomerResponse> changed = update(customer.id(), customer.etag(),
                new CustomerUpdateRequest(null, newEmail, "Audited Name"), CustomerResponse.class);
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);

        // The same values again: nothing changes, so the version stays put
        // and no event is written.
        String etag = changed.getHeaders().getETag();
        ResponseEntity<CustomerResponse> noOp = update(customer.id(), etag,
                new CustomerUpdateRequest(null, newEmail, "Audited Name"), CustomerResponse.class);
        assertThat(noOp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(noOp.getHeaders().getETag()).isEqualTo(etag);

        JsonNode history = auditHistory(TENANT, "CUSTOMER", customer.id());
        assertThat(history).extracting(event -> event.get("type").asText())
                .containsExactly("CUSTOMER_UPDATED", "CUSTOMER_CREATED");
        JsonNode updated = history.get(0);
        assertThat(updated.get("data").get("changedFields")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("email", "name");
        assertThat(updated.toString()).doesNotContain(newEmail).doesNotContain("Audited Name");
    }

    @Test
    void concurrentUpdatesFromTheSameRead_applyExactlyOne() throws Exception {
        // Every request carries the same ETag, as if several people opened the
        // same customer and saved at once. Requests that load after the winner
        // commits fail the If-Match check (412); requests that loaded before it
        // lose at flush, when the versioned UPDATE matches no row (409). Either
        // way exactly one edit lands, and nothing is silently overwritten.
        Created customer = createCustomer();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<HttpStatus>> calls = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                // Distinct names: an identical edit would be a no-op, which
                // writes nothing and so never reaches the version check.
                String name = "Concurrent " + i;
                calls.add(() -> HttpStatus.valueOf(update(customer.id(), customer.etag(),
                        new CustomerUpdateRequest(null, customer.email(), name), String.class)
                        .getStatusCode().value()));
            }
            List<HttpStatus> statuses = new ArrayList<>();
            for (Future<HttpStatus> result : pool.invokeAll(calls)) {
                statuses.add(result.get());
            }

            assertThat(statuses).filteredOn(HttpStatus.OK::equals).hasSize(1);
            assertThat(statuses).filteredOn(status -> status != HttpStatus.OK)
                    .hasSize(threads - 1)
                    .allMatch(status -> status == HttpStatus.PRECONDITION_FAILED || status == HttpStatus.CONFLICT);
            assertThat(auditHistory(TENANT, "CUSTOMER", customer.id()))
                    .extracting(event -> event.get("type").asText())
                    .containsExactly("CUSTOMER_UPDATED", "CUSTOMER_CREATED");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aWriteCommittedAfterTheReadButBeforeTheFlush_failsTheVersionedUpdate() throws Exception {
        // The narrow window the If-Match check cannot see: the version matched
        // when this transaction loaded the customer, and another write commits
        // before it flushes. Forced deterministically here, because the HTTP
        // concurrency test above accepts 412 or 409 and so cannot prove this
        // path ever ran. The exception type is what ProblemDetailsAdvice maps
        // to 409 CONCURRENT_MODIFICATION.
        Created customer = createCustomer();

        assertThatThrownBy(() -> TenantContext.runAs(TENANT, () -> transactionTemplate.executeWithoutResult(status -> {
            CustomerEntity loaded = customers.findByTenantIdAndId(TENANT, customer.id()).orElseThrow();
            loaded.setName("Loses The Race");
            // Another connection, so its own transaction: on this thread
            // JdbcTemplate would join the JPA transaction instead.
            runOnAnotherThread(() -> jdbcTemplate.update(
                    "UPDATE customer SET name = ?, version = version + 1 WHERE id = ?",
                    "Wins The Race", customer.id()));
        }))).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(get(customer.id()).getBody().name()).isEqualTo("Wins The Race");
    }

    // ---- helpers ----

    private static void runOnAnotherThread(Runnable action) {
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            other.submit(action).get();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        } finally {
            other.shutdownNow();
        }
    }

    private record Created(UUID id, String email, String etag) {
    }

    private Created createCustomer() {
        String email = "update-" + UUID.randomUUID() + "@example.com";
        ResponseEntity<CustomerResponse> response = restTemplate.exchange(
                "/api/customers", HttpMethod.POST,
                new HttpEntity<>(new CustomerCreateRequest(null, email, "Original Name"), tenantHeaders(TENANT)),
                CustomerResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getETag()).as("ETag on create").isNotNull();
        return new Created(response.getBody().id(), email, response.getHeaders().getETag());
    }

    private ResponseEntity<CustomerResponse> get(UUID id) {
        ResponseEntity<CustomerResponse> response = restTemplate.exchange(
                "/api/customers/" + id, HttpMethod.GET,
                new HttpEntity<>(tenantHeaders(TENANT)), CustomerResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response;
    }

    private <T> ResponseEntity<T> update(UUID id, String etag, CustomerUpdateRequest request, Class<T> type) {
        return restTemplate.exchange(
                "/api/customers/" + id, HttpMethod.PUT,
                new HttpEntity<>(request, ifMatch(tenantHeaders(TENANT), etag)), type);
    }

    private static HttpHeaders ifMatch(HttpHeaders headers, String etag) {
        if (etag != null) {
            headers.setIfMatch(etag);
        }
        return headers;
    }
}
