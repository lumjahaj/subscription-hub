package dev.lumjahaj.subscription.hub.notification.app;

import dev.lumjahaj.subscription.hub.billing.domain.InvoiceIssuedListener;
import dev.lumjahaj.subscription.hub.billing.domain.InvoiceRepository;
import dev.lumjahaj.subscription.hub.billing.infra.jpa.InvoiceEntity;
import dev.lumjahaj.subscription.hub.common.api.ResourceNotFoundException;
import dev.lumjahaj.subscription.hub.customer.infra.jpa.CustomerEntity;
import dev.lumjahaj.subscription.hub.notification.domain.EmailContent;
import dev.lumjahaj.subscription.hub.notification.domain.EmailRenderer;
import dev.lumjahaj.subscription.hub.notification.domain.EmailTemplate;
import dev.lumjahaj.subscription.hub.notification.domain.NotificationRepository;
import dev.lumjahaj.subscription.hub.notification.domain.NotificationType;
import dev.lumjahaj.subscription.hub.notification.infra.jpa.NotificationEntity;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Enqueues customer emails into the notification outbox. Rendering happens
 * here, inside the caller's transaction: it is local CPU work with no
 * remote call, so it does not violate the "no remote call inside a
 * transaction" rule that keeps the actual send (SmtpNotificationSender) and
 * publish (SqsNotificationPublisher) out of one.
 *
 * Every enqueue method is a check-then-insert on the dedup key. That is
 * only a backstop: {@code uk_notification_tenant_dedup_key} is what
 * actually stops a concurrent duplicate, and if it ever fired it would roll
 * back whichever settlement or billing transaction this joined - the same
 * caution PaymentService documents for its own idempotency check. Callers
 * are already idempotent by state (an invoice is only issued once, a
 * dunning attempt count only reaches a given number once), so in practice
 * the constraint is never expected to fire.
 */
@Service
public class NotificationService implements InvoiceIssuedListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final NotificationRepository notifications;
    private final InvoiceRepository invoices;
    private final EmailRenderer renderer;

    public NotificationService(
            NotificationRepository notifications,
            InvoiceRepository invoices,
            EmailRenderer renderer
    ) {
        this.notifications = notifications;
        this.invoices = invoices;
        this.renderer = renderer;
    }

    /**
     * Reloads the invoice rather than trusting the caller passed the right
     * one - InvoiceService calls this synchronously in the same transaction
     * right after saving, so the row is guaranteed to be there.
     */
    @Override
    @Transactional
    public void onInvoiceIssued(UUID invoiceId) {
        String tenantId = TenantContext.getTenantId();
        InvoiceEntity invoice = invoices.findByTenantIdAndId(tenantId, invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice", invoiceId.toString()));

        Map<String, Object> model = new HashMap<>();
        CustomerEntity customer = invoice.getCustomer();
        String subject = "Invoice " + invoice.getNumber() + " is ready";
        model.put("subject", subject);
        model.put("customerName", customer.getName());
        model.put("invoiceNumber", invoice.getNumber());
        model.put("amount", formatCents(invoice.getTotalCents()));
        model.put("currency", invoice.getCurrency());
        model.put("dueDate", formatDate(invoice.getDueAt()));

        enqueue(tenantId, NotificationType.INVOICE_ISSUED, "invoice-issued:" + invoiceId,
                customer.getEmail(), EmailTemplate.INVOICE_ISSUED, model, invoice);
    }

    /**
     * Called by DunningService with the invoice it already has loaded, and
     * the attempt count and next-attempt time its own state row just
     * computed - reloading here would be a second query for data the
     * caller is already holding.
     */
    @Transactional
    public void enqueuePaymentFailed(InvoiceEntity invoice, int attemptCount, Instant nextAttemptAt) {
        String tenantId = TenantContext.getTenantId();
        CustomerEntity customer = invoice.getCustomer();
        String subject = "We couldn't collect payment for invoice " + invoice.getNumber();

        Map<String, Object> model = new HashMap<>();
        model.put("subject", subject);
        model.put("customerName", customer.getName());
        model.put("invoiceNumber", invoice.getNumber());
        model.put("amount", formatCents(invoice.getTotalCents()));
        model.put("currency", invoice.getCurrency());
        model.put("nextAttemptDate", formatDate(nextAttemptAt));

        String dedupKey = "payment-failed:" + invoice.getId() + ":" + attemptCount;
        enqueue(tenantId, NotificationType.PAYMENT_FAILED, dedupKey,
                customer.getEmail(), EmailTemplate.PAYMENT_FAILED, model, invoice);
    }

    /**
     * The other half of enqueuePaymentFailed: a customer who was told a
     * payment failed is told when one finally goes through.
     *
     * DunningService calls this only when the subscription actually came back
     * from PAST_DUE, not merely because a dunning row existed. A first
     * automatic attempt that succeeds creates a dunning row and sends no
     * failure email, so triggering on the row would tell a customer their
     * subscription had recovered from a problem they were never told about —
     * and "your subscription is active again" would be a lie if a pause or
     * cancellation had meanwhile refused the transition.
     *
     * The attempt count is deliberately not in the model. How many times we
     * tried the customer's card is our operational detail, not something to
     * put in front of them.
     */
    @Transactional
    public void enqueuePaymentRecovered(InvoiceEntity invoice) {
        String tenantId = TenantContext.getTenantId();
        CustomerEntity customer = invoice.getCustomer();
        String subject = "We've received your payment for invoice " + invoice.getNumber();

        Map<String, Object> model = new HashMap<>();
        model.put("subject", subject);
        model.put("customerName", customer.getName());
        model.put("invoiceNumber", invoice.getNumber());
        model.put("amount", formatCents(invoice.getTotalCents()));
        model.put("currency", invoice.getCurrency());

        // No attempt suffix, unlike payment-failed: an invoice recovers at
        // most once, because settlement only pays an OPEN invoice and this
        // one is already PAID by the time we get here.
        enqueue(tenantId, NotificationType.PAYMENT_RECOVERED, "payment-recovered:" + invoice.getId(),
                customer.getEmail(), EmailTemplate.PAYMENT_RECOVERED, model, invoice);
    }

    @Transactional
    public void enqueueSubscriptionCanceled(InvoiceEntity invoice) {
        String tenantId = TenantContext.getTenantId();
        CustomerEntity customer = invoice.getCustomer();
        String subject = "Your subscription has been canceled";

        Map<String, Object> model = new HashMap<>();
        model.put("subject", subject);
        model.put("customerName", customer.getName());
        model.put("invoiceNumber", invoice.getNumber());

        enqueue(tenantId, NotificationType.SUBSCRIPTION_CANCELED, "subscription-canceled:" + invoice.getId(),
                customer.getEmail(), EmailTemplate.SUBSCRIPTION_CANCELED, model, invoice);
    }

    private void enqueue(
            String tenantId, NotificationType type, String dedupKey, String recipient,
            EmailTemplate template, Map<String, Object> model, InvoiceEntity invoice
    ) {
        if (notifications.findByTenantIdAndDedupKey(tenantId, dedupKey).isPresent()) {
            log.debug("Notification {} already enqueued, skipping", dedupKey);
            return;
        }

        EmailContent content = renderer.render(template, model);

        NotificationEntity notification = new NotificationEntity();
        notification.setTenantId(tenantId);
        notification.setType(type);
        notification.setDedupKey(dedupKey);
        notification.setRecipient(recipient);
        notification.setSubject(content.subject());
        notification.setHtmlBody(content.html());
        notification.setTextBody(content.text());
        notification.setInvoice(invoice);
        notifications.save(notification);
    }

    // Same conversion InvoicePdfRenderer uses: repositioning the decimal
    // point on an exact integer, no division and no floating point.
    private static String formatCents(long cents) {
        return BigDecimal.valueOf(cents, 2).toPlainString();
    }

    private static String formatDate(Instant instant) {
        return instant == null ? "" : DATE.format(instant);
    }
}
