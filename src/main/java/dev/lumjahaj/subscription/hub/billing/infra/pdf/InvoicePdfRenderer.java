package dev.lumjahaj.subscription.hub.billing.infra.pdf;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import dev.lumjahaj.subscription.hub.billing.infra.jpa.InvoiceEntity;
import dev.lumjahaj.subscription.hub.billing.infra.jpa.InvoiceLineEntity;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Renders an invoice to PDF by running a Thymeleaf-templated HTML
 * document through openhtmltopdf.
 *
 * The alternative — drawing to a PDF canvas with PDFBox directly — means
 * hand-computing row offsets, column widths and string widths to
 * right-align money, plus page-break handling, all of which this gets for
 * free from the HTML/CSS engine. It also puts the layout in a template a
 * non-Java reader can edit.
 */
@Component
public class InvoicePdfRenderer {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final TemplateEngine templateEngine;

    /**
     * The TemplateEngine is built here rather than exposed as a bean:
     * nothing else in the application uses Thymeleaf (this is a JSON API
     * with no server-rendered views), and a container-wide TemplateEngine
     * would invite accidental coupling to a template engine the project
     * deliberately doesn't have.
     */
    public InvoicePdfRenderer() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");

        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        this.templateEngine = engine;
    }

    public byte[] render(InvoiceEntity invoice) {
        Context context = new Context();
        context.setVariable("invoice", toView(invoice));
        String html = templateEngine.process("invoice", context);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
        } catch (IOException e) {
            // Writing to a ByteArrayOutputStream cannot realistically fail
            // on I/O; this means the renderer itself rejected the document.
            // Wrapping keeps a checked exception out of the call chain,
            // matching how PlanEntitlementMapper handles the same
            // shouldn't-happen shape.
            throw new IllegalStateException("Failed to render invoice PDF: " + invoice.getNumber(), e);
        }
        return out.toByteArray();
    }

    private static InvoicePdfView toView(InvoiceEntity invoice) {
        List<InvoicePdfView.Line> lines = invoice.getLines().stream()
                .map(InvoicePdfRenderer::toLineView)
                .toList();

        return new InvoicePdfView(
                invoice.getNumber(),
                invoice.getStatus().name(),
                invoice.getCurrency(),
                formatDate(invoice.getIssuedAt()),
                formatDate(invoice.getDueAt()),
                formatDate(invoice.getPeriodStart()),
                formatDate(invoice.getPeriodEnd()),
                invoice.getCustomer().getName(),
                invoice.getCustomer().getEmail(),
                formatCents(invoice.getTotalCents()),
                lines);
    }

    private static InvoicePdfView.Line toLineView(InvoiceLineEntity line) {
        return new InvoicePdfView.Line(
                line.getKind().name(),
                line.getDescription(),
                line.getQuantity().stripTrailingZeros().toPlainString(),
                formatCents(line.getUnitAmountCents()),
                formatCents(line.getAmountCents()));
    }

    /**
     * Integer minor units to a decimal display string: 2999 -> "29.99".
     *
     * BigDecimal.valueOf(long, int scale) just repositions the decimal
     * point on the exact integer value — no division, no floating point,
     * so the money rule holds right up to the moment the number becomes
     * text.
     */
    private static String formatCents(long cents) {
        return BigDecimal.valueOf(cents, 2).toPlainString();
    }

    private static String formatDate(Instant instant) {
        return instant == null ? "" : DATE.format(instant);
    }
}
