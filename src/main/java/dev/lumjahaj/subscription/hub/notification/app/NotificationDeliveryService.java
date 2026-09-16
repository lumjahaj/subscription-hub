package dev.lumjahaj.subscription.hub.notification.app;

import dev.lumjahaj.subscription.hub.billing.app.InvoicePdfAlreadyGeneratedException;
import dev.lumjahaj.subscription.hub.billing.app.InvoicePdfDownload;
import dev.lumjahaj.subscription.hub.billing.app.InvoicePdfService;
import dev.lumjahaj.subscription.hub.notification.domain.Attachment;
import dev.lumjahaj.subscription.hub.notification.domain.NotificationRepository;
import dev.lumjahaj.subscription.hub.notification.domain.NotificationSender;
import dev.lumjahaj.subscription.hub.notification.domain.NotificationStatus;
import dev.lumjahaj.subscription.hub.notification.domain.OutgoingEmail;
import dev.lumjahaj.subscription.hub.notification.infra.jpa.NotificationEntity;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Sends one queued notification. Called by {@code SqsNotificationListener}
 * with the tenant already set in {@link dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext}.
 *
 * Three steps, the same "load in a transaction, act with none open, record
 * in a transaction" split as PaymentService and InvoicePdfService: a remote
 * call (SMTP, and possibly a PDF render/upload first) must never run inside
 * one.
 */
@Service
public class NotificationDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryService.class);

    private final NotificationRepository notifications;
    private final InvoicePdfService invoicePdfService;
    private final NotificationSender sender;

    public NotificationDeliveryService(
            NotificationRepository notifications,
            InvoicePdfService invoicePdfService,
            NotificationSender sender
    ) {
        this.notifications = notifications;
        this.invoicePdfService = invoicePdfService;
        this.sender = sender;
    }

    public void deliver(UUID notificationId) {
        String tenantId = TenantContext.getTenantId();
        NotificationForDelivery notification = loadForDelivery(tenantId, notificationId);
        if (notification == null) {
            return;
        }

        try {
            Optional<Attachment> attachment = notification.invoiceId() == null
                    ? Optional.empty()
                    : Optional.of(pdfAttachmentFor(notification.invoiceId()));

            sender.send(new OutgoingEmail(
                    notification.recipient(), notification.subject(),
                    notification.html(), notification.text(), attachment));

            markSent(tenantId, notificationId);
        } catch (Exception ex) {
            recordFailure(tenantId, notificationId, ex);
            // Rethrown deliberately: SqsNotificationListener must see this
            // so the message is not acknowledged and SQS redelivers it.
            throw new NotificationDeliveryException("Failed to deliver notification " + notificationId, ex);
        }
    }

    /**
     * Already-SENT is treated as a no-op, not an error: at-least-once
     * delivery means the same message can be redelivered or the relay can
     * publish the same row twice, and this is what makes a duplicate
     * harmless rather than a duplicate email.
     */
    @Transactional(readOnly = true)
    NotificationForDelivery loadForDelivery(String tenantId, UUID id) {
        NotificationEntity notification = notifications.findByTenantIdAndId(tenantId, id).orElse(null);
        if (notification == null) {
            log.warn("Ignoring delivery for unknown notification {}", id);
            return null;
        }
        if (notification.getStatus() == NotificationStatus.SENT) {
            log.debug("Notification {} already sent, ignoring duplicate delivery", id);
            return null;
        }
        return new NotificationForDelivery(
                notification.getRecipient(),
                notification.getSubject(),
                notification.getHtmlBody(),
                notification.getTextBody(),
                notification.getInvoice() == null ? null : notification.getInvoice().getId());
    }

    /**
     * Generates the PDF if the billing cycle's own attempt hasn't happened
     * yet (or failed) - the same self-healing this dispatcher gives
     * InvoicePdfService.generatePdf's other caller, BillingCycleJob, for
     * free. The "already generated" race is expected, not an error: another
     * run, or a manual POST /api/invoices/{id}/pdf, may have beaten this one.
     */
    private Attachment pdfAttachmentFor(UUID invoiceId) {
        try {
            invoicePdfService.generatePdf(invoiceId);
        } catch (InvoicePdfAlreadyGeneratedException alreadyGenerated) {
            // Fine - the PDF exists, which is all this needs.
        }

        InvoicePdfDownload download = invoicePdfService.download(invoiceId);
        try (InputStream content = download.pdf().content()) {
            return new Attachment(download.filename(), "application/pdf", content.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read PDF for invoice " + invoiceId, e);
        }
    }

    @Transactional
    void markSent(String tenantId, UUID id) {
        notifications.findByTenantIdAndId(tenantId, id).ifPresent(notification -> {
            notification.setStatus(NotificationStatus.SENT);
            notification.setSentAt(Instant.now());
            notifications.save(notification);
        });
    }

    @Transactional
    void recordFailure(String tenantId, UUID id, Exception ex) {
        notifications.findByTenantIdAndId(tenantId, id).ifPresent(notification -> {
            notification.setDeliveryAttempts(notification.getDeliveryAttempts() + 1);
            notification.setLastError(ex.toString());
            notifications.save(notification);
        });
    }
}
