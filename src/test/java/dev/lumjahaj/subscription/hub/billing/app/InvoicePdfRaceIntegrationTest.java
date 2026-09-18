package dev.lumjahaj.subscription.hub.billing.app;

import dev.lumjahaj.subscription.hub.billing.domain.InvoiceRepository;
import dev.lumjahaj.subscription.hub.billing.infra.jpa.InvoiceEntity;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanResponse;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductResponse;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerCreateRequest;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerResponse;
import dev.lumjahaj.subscription.hub.billing.api.dto.InvoiceResponse;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionCreateRequest;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionResponse;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import dev.lumjahaj.subscription.hub.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Storing an invoice's PDF must never write any other column.
 *
 * The same forced-race shape as SubscriptionTransitionRaceIntegrationTest: a
 * transaction first reads the invoice, another connection commits a payment
 * settling it PAID, and only then does the PDF key get recorded inside that
 * stale transaction. That is exactly the window generatePdf opens, because it
 * loads the invoice and then uploads to the object store before recording the
 * key - and the upload is slow.
 *
 * Before the fix, recording the key meant setting the field on the loaded
 * entity and saving it, so Hibernate wrote every column from its stale
 * snapshot and put the invoice back to OPEN with a null paid_at. The customer
 * had paid, the payment row said SUCCEEDED, and dunning would collect the same
 * invoice again.
 *
 * Only a live run surfaced it: the outbox relay is parked at a day's delay in
 * the test suite, so nothing there ever generated a PDF while a payment was
 * settling.
 */
class InvoicePdfRaceIntegrationTest extends AbstractIntegrationTest {

    private static final String TENANT = "acme";

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private InvoiceRepository invoices;

    @Autowired
    private InvoicePdfService invoicePdfService;

    @Test
    void aPaymentCommittedWhileThePdfUploads_isNotRevertedWhenTheKeyIsRecorded() {
        UUID invoiceId = openInvoice();

        TenantContext.runAs(TENANT, () -> transactionTemplate.executeWithoutResult(status -> {
            // 1. The transaction reads the invoice, as generatePdf does before
            //    it renders and uploads. Its snapshot says OPEN.
            InvoiceEntity loaded = invoices.findByTenantIdAndId(TENANT, invoiceId).orElseThrow();
            assertThat(loaded.getStatus().name()).isEqualTo("OPEN");

            // 2. The payment settles on another connection and commits.
            commitOnAnotherConnection(
                    "UPDATE invoice SET status = 'PAID', paid_at = now() WHERE id = ?", invoiceId);

            // 3. The key is recorded from inside the now-stale transaction.
            assertThat(invoices.attachPdfObjectKeyIfAbsent(TENANT, invoiceId, "acme/INV-TEST.pdf")).isTrue();
        }));

        assertThat(invoiceStatus(invoiceId)).as("the settled payment survives").isEqualTo("PAID");
        assertThat(paidAt(invoiceId)).as("paid_at is not cleared").isNotNull();
        assertThat(pdfObjectKey(invoiceId)).isEqualTo("acme/INV-TEST.pdf");
    }

    @Test
    void recordingTheKeyTwice_onlySucceedsOnce() {
        // Two generations racing: the guard is what makes the second one a
        // reported conflict rather than both silently claiming success.
        UUID invoiceId = openInvoice();

        // Inside a transaction because a @Modifying query requires one, which
        // is why InvoicePdfService calls this from its recording transaction
        // rather than bare.
        TenantContext.runAs(TENANT, () -> transactionTemplate.executeWithoutResult(status -> {
            assertThat(invoices.attachPdfObjectKeyIfAbsent(TENANT, invoiceId, "acme/first.pdf")).isTrue();
            assertThat(invoices.attachPdfObjectKeyIfAbsent(TENANT, invoiceId, "acme/second.pdf")).isFalse();
        }));

        assertThat(pdfObjectKey(invoiceId)).isEqualTo("acme/first.pdf");
    }

    @Test
    void generatingThePdfForAPaidInvoice_leavesItPaid() {
        // End to end through the real service and a real MinIO container, so a
        // future change that goes back to saving the whole entity is caught
        // here even without the forced race above.
        UUID invoiceId = openInvoice();
        jdbcTemplate.update("UPDATE invoice SET status = 'PAID', paid_at = now() WHERE id = ?", invoiceId);

        TenantContext.runAs(TENANT, () -> invoicePdfService.generatePdf(invoiceId));

        assertThat(invoiceStatus(invoiceId)).isEqualTo("PAID");
        assertThat(paidAt(invoiceId)).isNotNull();
        assertThat(pdfObjectKey(invoiceId)).isNotNull();
    }

    // ---- race plumbing ----

    /**
     * On this thread the update would join the open JPA transaction instead of
     * committing first, so it has to run on another one.
     */
    private void commitOnAnotherConnection(String sql, UUID id) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> jdbcTemplate.update(sql, id)).get();
        } catch (Exception e) {
            throw new IllegalStateException("Competing update failed", e);
        } finally {
            executor.shutdown();
        }
    }

    // ---- state helpers ----

    private String invoiceStatus(UUID invoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT status::text FROM invoice WHERE id = ?", String.class, invoiceId);
    }

    private Timestamp paidAt(UUID invoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT paid_at FROM invoice WHERE id = ?", Timestamp.class, invoiceId);
    }

    private String pdfObjectKey(UUID invoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT pdf_object_key FROM invoice WHERE id = ?", String.class, invoiceId);
    }

    // ---- fixture ----

    private UUID openInvoice() {
        String suffix = UUID.randomUUID().toString();

        var productRequest = new ProductCreateRequest("pdf-race-product-" + suffix, "PDF Race Product", null);
        ResponseEntity<ProductResponse> product = restTemplate.exchange(
                "/api/products", HttpMethod.POST,
                new HttpEntity<>(productRequest, tenantHeaders(TENANT)), ProductResponse.class);
        assertThat(product.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var planRequest = new PlanCreateRequest(
                product.getBody().code(), "pdf-race-plan-" + suffix, "PDF Race Plan",
                "MONTH", 1, 2999L, "USD", 0);
        ResponseEntity<PlanResponse> plan = restTemplate.exchange(
                "/api/plans", HttpMethod.POST,
                new HttpEntity<>(planRequest, tenantHeaders(TENANT)), PlanResponse.class);
        assertThat(plan.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var customerRequest = new CustomerCreateRequest(
                null, "pdf-race-" + suffix + "@example.com", "PDF Race Customer");
        ResponseEntity<CustomerResponse> customer = restTemplate.exchange(
                "/api/customers", HttpMethod.POST,
                new HttpEntity<>(customerRequest, tenantHeaders(TENANT)), CustomerResponse.class);
        assertThat(customer.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var subscriptionRequest = new SubscriptionCreateRequest(customer.getBody().id(), plan.getBody().code());
        ResponseEntity<SubscriptionResponse> subscription = restTemplate.exchange(
                "/api/subscriptions", HttpMethod.POST,
                new HttpEntity<>(subscriptionRequest, tenantHeaders(TENANT)), SubscriptionResponse.class);
        assertThat(subscription.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID subscriptionId = subscription.getBody().id();

        jdbcTemplate.update("UPDATE subscription SET current_period_end = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), subscriptionId);

        ResponseEntity<InvoiceResponse> invoice = restTemplate.exchange(
                "/api/subscriptions/" + subscriptionId + "/invoices", HttpMethod.POST,
                new HttpEntity<>(tenantHeaders(TENANT)), InvoiceResponse.class);
        assertThat(invoice.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return invoice.getBody().id();
    }
}
