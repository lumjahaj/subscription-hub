package dev.lumjahaj.subscription.hub.billing.infra.pdf;

import dev.lumjahaj.subscription.hub.billing.domain.InvoiceLineKind;
import dev.lumjahaj.subscription.hub.billing.domain.InvoiceStatus;
import dev.lumjahaj.subscription.hub.billing.infra.jpa.InvoiceEntity;
import dev.lumjahaj.subscription.hub.billing.infra.jpa.InvoiceLineEntity;
import dev.lumjahaj.subscription.hub.customer.infra.jpa.CustomerEntity;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure - no Spring, no container. Asserts on the actual rendered
 * document rather than just "some bytes came back": PDFBox (on the
 * classpath transitively via openhtmltopdf) reopens the output and
 * extracts its text, so a template that silently drops a line item or
 * mangles an amount fails here rather than being noticed by a human
 * looking at a PDF weeks later.
 */
class InvoicePdfRendererTest {

    private final InvoicePdfRenderer renderer = new InvoicePdfRenderer();

    @Test
    void render_producesAParseableSinglePagePdf() throws IOException {
        byte[] pdf = renderer.render(invoice());

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    void render_includesTheInvoiceNumberStatusAndCustomer() throws IOException {
        String text = textOf(renderer.render(invoice()));

        assertThat(text).contains("INV-000001");
        assertThat(text).contains("OPEN");
        assertThat(text).contains("Acme Customer");
        assertThat(text).contains("billing@acme.test");
    }

    @Test
    void render_includesEveryLineItem() throws IOException {
        String text = textOf(renderer.render(invoice()));

        assertThat(text).contains("Pro Plan (pro)");
        assertThat(text).contains("api.calls (50 billable)");
        assertThat(text).contains("BASE");
        assertThat(text).contains("USAGE");
    }

    @Test
    void render_formatsCentsAsDecimalCurrencyNeverRawIntegers() throws IOException {
        String text = textOf(renderer.render(invoice()));

        // 2999 cents is 29.99, not 2999; 5 cents is 0.05; the 3249 total
        // is 32.49. The raw cent values must not appear anywhere.
        assertThat(text).contains("29.99");
        assertThat(text).contains("0.05");
        assertThat(text).contains("32.49");
        assertThat(text).doesNotContain("2999");
        assertThat(text).doesNotContain("3249");
    }

    @Test
    void render_showsTheCurrencyAlongsideAmounts() throws IOException {
        String text = textOf(renderer.render(invoice()));

        assertThat(text).contains("29.99 USD");
        assertThat(text).contains("Amounts are shown in USD.");
    }

    private static String textOf(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    /**
     * Built by hand rather than through InvoiceService, the same way
     * InvoiceCalculatorTest does - this test is about the template, so it
     * has no business touching a repository or a database.
     */
    private static InvoiceEntity invoice() {
        CustomerEntity customer = new CustomerEntity();
        customer.setId(UUID.randomUUID());
        customer.setName("Acme Customer");
        customer.setEmail("billing@acme.test");

        InvoiceEntity invoice = new InvoiceEntity();
        invoice.setId(UUID.randomUUID());
        invoice.setCustomer(customer);
        invoice.setNumber("INV-000001");
        invoice.setStatus(InvoiceStatus.OPEN);
        invoice.setCurrency("USD");
        invoice.setTotalCents(3249);
        invoice.setPeriodStart(Instant.parse("2026-01-01T00:00:00Z"));
        invoice.setPeriodEnd(Instant.parse("2026-02-01T00:00:00Z"));
        invoice.setIssuedAt(Instant.parse("2026-02-01T00:05:00Z"));
        invoice.setDueAt(Instant.parse("2026-02-15T00:05:00Z"));

        invoice.addLine(line(InvoiceLineKind.BASE, "Pro Plan (pro)", "1", 2999, 2999));
        invoice.addLine(line(InvoiceLineKind.USAGE, "api.calls (50 billable)", "50", 5, 250));
        return invoice;
    }

    private static InvoiceLineEntity line(
            InvoiceLineKind kind, String description, String quantity, long unitAmountCents, long amountCents) {
        InvoiceLineEntity line = new InvoiceLineEntity();
        line.setKind(kind);
        line.setDescription(description);
        line.setQuantity(new BigDecimal(quantity));
        line.setUnitAmountCents(unitAmountCents);
        line.setAmountCents(amountCents);
        return line;
    }
}
