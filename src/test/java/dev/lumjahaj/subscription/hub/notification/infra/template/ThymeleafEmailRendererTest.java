package dev.lumjahaj.subscription.hub.notification.infra.template;

import dev.lumjahaj.subscription.hub.notification.domain.EmailContent;
import dev.lumjahaj.subscription.hub.notification.domain.EmailTemplate;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * No Spring context, no containers - just the Thymeleaf engine, the same
 * level InvoicePdfRenderer would be tested at.
 */
class ThymeleafEmailRendererTest {

    private final ThymeleafEmailRenderer renderer = new ThymeleafEmailRenderer();

    @Test
    void rendersTheInvoiceIssuedEmail_withSubjectAndBothBodies() {
        EmailContent content = renderer.render(EmailTemplate.INVOICE_ISSUED, Map.of(
                "subject", "Invoice INV-000001 is ready",
                "customerName", "Jane Doe",
                "invoiceNumber", "INV-000001",
                "amount", "29.99",
                "currency", "USD",
                "dueDate", "2026-02-01"
        ));

        assertThat(content.subject()).isEqualTo("Invoice INV-000001 is ready");
        assertThat(content.html()).contains("Jane Doe", "INV-000001", "29.99", "USD", "2026-02-01");
        assertThat(content.text()).contains("Jane Doe", "INV-000001", "29.99", "USD", "2026-02-01");
    }

    @Test
    void theTextBody_hasNoHtmlTags() {
        EmailContent content = renderer.render(EmailTemplate.PAYMENT_FAILED, Map.of(
                "subject", "We couldn't collect payment",
                "customerName", "Jane Doe",
                "invoiceNumber", "INV-000002",
                "amount", "10.00",
                "currency", "USD",
                "nextAttemptDate", "2026-02-05"
        ));

        assertThat(content.text()).doesNotContain("<", ">");
    }

    @Test
    void aCustomerNameWithMarkup_comesOutEscapedInTheHtmlBody() {
        EmailContent content = renderer.render(EmailTemplate.SUBSCRIPTION_CANCELED, Map.of(
                "subject", "Your subscription has been canceled",
                "customerName", "<script>alert(1)</script>",
                "invoiceNumber", "INV-000003"
        ));

        assertThat(content.html()).doesNotContain("<script>");
        assertThat(content.html()).contains("&lt;script&gt;");
    }

    @Test
    void aMissingSubject_isRejectedRatherThanSilentlySendingWithoutOne() {
        assertThatThrownBy(() -> renderer.render(EmailTemplate.INVOICE_ISSUED, Map.of(
                "customerName", "Jane Doe",
                "invoiceNumber", "INV-000004",
                "amount", "1.00",
                "currency", "USD",
                "dueDate", "2026-02-01"
        ))).isInstanceOf(IllegalArgumentException.class);
    }
}
