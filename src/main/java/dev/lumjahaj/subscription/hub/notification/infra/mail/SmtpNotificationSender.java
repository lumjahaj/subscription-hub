package dev.lumjahaj.subscription.hub.notification.infra.mail;

import dev.lumjahaj.subscription.hub.notification.domain.Attachment;
import dev.lumjahaj.subscription.hub.notification.domain.NotificationSender;
import dev.lumjahaj.subscription.hub.notification.domain.OutgoingEmail;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Delivers over SMTP - Mailpit locally and in tests, Amazon SES's SMTP
 * interface in production, a host change rather than a code change (the
 * same "vendor is a config value" shape as MinIO/S3 and fake/Stripe).
 *
 * {@code setText(text, html)} produces a multipart/alternative body (text
 * first, then HTML - clients prefer the richer part they understand and
 * fall back to the other), wrapped in multipart/mixed with the PDF attached
 * alongside when there is one.
 */
@Component
public class SmtpNotificationSender implements NotificationSender {

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpNotificationSender(JavaMailSender mailSender, @Value("${notification.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(OutgoingEmail email) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(
                    message, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, "UTF-8");
            helper.setFrom(from);
            helper.setTo(email.recipient());
            helper.setSubject(email.subject());
            helper.setText(email.text(), email.html());

            for (Attachment attachment : email.attachment().stream().toList()) {
                helper.addAttachment(attachment.filename(),
                        new ByteArrayResource(attachment.bytes()), attachment.contentType());
            }
        } catch (jakarta.mail.MessagingException ex) {
            // A malformed message (not a delivery failure - that comes from
            // mailSender.send below). Either way the caller's retry/DLQ
            // handling is the same, so both surface as the same runtime
            // exception type.
            throw new IllegalStateException("Failed to build email to " + email.recipient(), ex);
        }
        mailSender.send(message);
    }
}
