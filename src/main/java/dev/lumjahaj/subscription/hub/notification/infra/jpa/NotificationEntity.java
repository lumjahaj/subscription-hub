package dev.lumjahaj.subscription.hub.notification.infra.jpa;

import dev.lumjahaj.subscription.hub.billing.infra.jpa.InvoiceEntity;
import dev.lumjahaj.subscription.hub.notification.domain.NotificationStatus;
import dev.lumjahaj.subscription.hub.notification.domain.NotificationType;
import dev.lumjahaj.subscription.hub.tenancy.infra.jpa.TenantScoped;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification", uniqueConstraints = {
        @UniqueConstraint(name = "uk_notification_tenant_dedup_key", columnNames = {"tenant_id", "dedup_key"})
})
public class NotificationEntity extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Plain @Enumerated(STRING), not the NAMED_ENUM combo: this is a
    // varchar(32) with a CHECK constraint, not a Postgres native enum type -
    // the same choice already made for invoice_line.kind (CLAUDE.md §5).
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private NotificationType type;

    @Column(name = "dedup_key", nullable = false, length = 200)
    private String dedupKey;

    @Column(name = "recipient", nullable = false, length = 320)
    private String recipient;

    @Column(name = "subject", nullable = false, length = 200)
    private String subject;

    @Column(name = "html_body", nullable = false)
    private String htmlBody;

    @Column(name = "text_body", nullable = false)
    private String textBody;

    // Nullable: not every notification carries a PDF. Set when the
    // listener needs to fetch and attach one.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id")
    private InvoiceEntity invoice;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private NotificationStatus status = NotificationStatus.PENDING;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "delivery_attempts", nullable = false)
    private int deliveryAttempts;

    @Column(name = "last_error")
    private String lastError;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public NotificationType getType() { return type; }
    public void setType(NotificationType type) { this.type = type; }
    public String getDedupKey() { return dedupKey; }
    public void setDedupKey(String dedupKey) { this.dedupKey = dedupKey; }
    public String getRecipient() { return recipient; }
    public void setRecipient(String recipient) { this.recipient = recipient; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getHtmlBody() { return htmlBody; }
    public void setHtmlBody(String htmlBody) { this.htmlBody = htmlBody; }
    public String getTextBody() { return textBody; }
    public void setTextBody(String textBody) { this.textBody = textBody; }
    public InvoiceEntity getInvoice() { return invoice; }
    public void setInvoice(InvoiceEntity invoice) { this.invoice = invoice; }
    public NotificationStatus getStatus() { return status; }
    public void setStatus(NotificationStatus status) { this.status = status; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }
    public int getDeliveryAttempts() { return deliveryAttempts; }
    public void setDeliveryAttempts(int deliveryAttempts) { this.deliveryAttempts = deliveryAttempts; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
}
