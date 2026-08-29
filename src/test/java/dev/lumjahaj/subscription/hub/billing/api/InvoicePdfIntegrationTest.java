package dev.lumjahaj.subscription.hub.billing.api;

import dev.lumjahaj.subscription.hub.billing.api.dto.InvoiceResponse;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanResponse;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductResponse;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerCreateRequest;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerResponse;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionCreateRequest;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionResponse;
import dev.lumjahaj.subscription.hub.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of PDF generation and download against a real
 * object store. InvoicePdfRendererTest already proves the document is
 * correct; what only a real MinIO can prove is that the bytes survive
 * the full round trip - uploaded under a tenant-prefixed key, fetched
 * back, and streamed out with the right headers - and that the object
 * store never lets one tenant reach another's invoice.
 */
class InvoicePdfIntegrationTest extends AbstractIntegrationTest {

    private static final String TENANT = "acme";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void generatePdf_forAnInvoice_returns201AndMarksPdfAvailable() {
        UUID invoiceId = createInvoice();

        ResponseEntity<InvoiceResponse> response = generatePdf(invoiceId, TENANT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().pdfAvailable()).isTrue();
        assertThat(response.getHeaders().getLocation()).hasPath("/api/invoices/" + invoiceId + "/pdf");
    }

    @Test
    void getInvoice_beforeGeneration_reportsPdfUnavailable() {
        UUID invoiceId = createInvoice();

        ResponseEntity<InvoiceResponse> response = restTemplate.exchange(
                "/api/invoices/" + invoiceId, HttpMethod.GET,
                new HttpEntity<>(tenantHeaders(TENANT)), InvoiceResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().pdfAvailable()).isFalse();
    }

    @Test
    void generatePdf_twice_returnsConflict() {
        UUID invoiceId = createInvoice();
        assertThat(generatePdf(invoiceId, TENANT).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> second = generatePdf(invoiceId, TENANT, String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("INVOICE_PDF_ALREADY_GENERATED");
    }

    @Test
    void downloadPdf_beforeGeneration_returnsNotFound() {
        UUID invoiceId = createInvoice();

        ResponseEntity<String> response = downloadPdf(invoiceId, TENANT, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("INVOICE_PDF_NOT_GENERATED");
    }

    @Test
    void downloadPdf_afterGeneration_streamsTheStoredPdf() {
        UUID invoiceId = createInvoice();
        String number = generatePdf(invoiceId, TENANT).getBody().number();

        ResponseEntity<byte[]> response = downloadPdf(invoiceId, TENANT, byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(response.getHeaders().getContentDisposition().getFilename()).isEqualTo(number + ".pdf");
        // The bytes really made the round trip through MinIO rather than
        // being regenerated on the way out - this is what a stubbed
        // storage adapter could not prove.
        assertThat(new String(response.getBody(), 0, 5)).isEqualTo("%PDF-");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(response.getBody().length);
    }

    @Test
    void generatePdf_forAnUnknownInvoice_returnsNotFound() {
        ResponseEntity<String> response = generatePdf(UUID.randomUUID(), TENANT, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("INVOICE_NOT_FOUND");
    }

    @Test
    void generatePdf_underAnotherTenant_returnsNotFound() {
        UUID invoiceId = createInvoice();

        ResponseEntity<String> response = generatePdf(invoiceId, "demo", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void downloadPdf_underAnotherTenant_returnsNotFound() {
        UUID invoiceId = createInvoice();
        generatePdf(invoiceId, TENANT);

        ResponseEntity<String> response = downloadPdf(invoiceId, "demo", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<InvoiceResponse> generatePdf(UUID invoiceId, String tenant) {
        return generatePdf(invoiceId, tenant, InvoiceResponse.class);
    }

    private <T> ResponseEntity<T> generatePdf(UUID invoiceId, String tenant, Class<T> responseType) {
        return restTemplate.exchange(
                "/api/invoices/" + invoiceId + "/pdf", HttpMethod.POST,
                new HttpEntity<>(tenantHeaders(tenant)), responseType);
    }

    private <T> ResponseEntity<T> downloadPdf(UUID invoiceId, String tenant, Class<T> responseType) {
        return restTemplate.exchange(
                "/api/invoices/" + invoiceId + "/pdf", HttpMethod.GET,
                new HttpEntity<>(tenantHeaders(tenant)), responseType);
    }

    /**
     * Seeds a subscription, forces its period closed, and invoices it —
     * an invoice can only exist for a closed period (see
     * InvoiceService), and there is no HTTP way to close one early.
     */
    private UUID createInvoice() {
        String suffix = UUID.randomUUID().toString();

        var productRequest = new ProductCreateRequest("pdf-test-product-" + suffix, "PDF Test Product", null);
        ResponseEntity<ProductResponse> productResponse = restTemplate.exchange(
                "/api/products", HttpMethod.POST,
                new HttpEntity<>(productRequest, tenantHeaders(TENANT)), ProductResponse.class);
        assertThat(productResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var planRequest = new PlanCreateRequest(
                productResponse.getBody().code(), "pdf-test-plan-" + suffix, "PDF Test Plan",
                "MONTH", 1, 2999L, "USD", 0);
        ResponseEntity<PlanResponse> planResponse = restTemplate.exchange(
                "/api/plans", HttpMethod.POST,
                new HttpEntity<>(planRequest, tenantHeaders(TENANT)), PlanResponse.class);
        assertThat(planResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var customerRequest = new CustomerCreateRequest(
                null, "pdf-" + suffix + "@example.com", "PDF Test Customer");
        ResponseEntity<CustomerResponse> customerResponse = restTemplate.exchange(
                "/api/customers", HttpMethod.POST,
                new HttpEntity<>(customerRequest, tenantHeaders(TENANT)), CustomerResponse.class);
        assertThat(customerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var subscriptionRequest = new SubscriptionCreateRequest(
                customerResponse.getBody().id(), planResponse.getBody().code());
        ResponseEntity<SubscriptionResponse> subscriptionResponse = restTemplate.exchange(
                "/api/subscriptions", HttpMethod.POST,
                new HttpEntity<>(subscriptionRequest, tenantHeaders(TENANT)), SubscriptionResponse.class);
        assertThat(subscriptionResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID subscriptionId = subscriptionResponse.getBody().id();

        jdbcTemplate.update(
                "UPDATE subscription SET current_period_end = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), subscriptionId);

        ResponseEntity<InvoiceResponse> invoiceResponse = restTemplate.exchange(
                "/api/subscriptions/" + subscriptionId + "/invoices", HttpMethod.POST,
                new HttpEntity<>(tenantHeaders(TENANT)), InvoiceResponse.class);
        assertThat(invoiceResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return invoiceResponse.getBody().id();
    }
}
