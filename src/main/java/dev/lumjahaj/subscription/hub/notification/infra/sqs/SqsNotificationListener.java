package dev.lumjahaj.subscription.hub.notification.infra.sqs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.lumjahaj.subscription.hub.notification.app.NotificationDeliveryService;
import dev.lumjahaj.subscription.hub.notification.domain.NotificationMessage;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the notification queue. A listener thread has no request, so
 * unlike PaymentWebhookService there is no open-in-view Hibernate session
 * bound before the tenant is known — the transaction inside
 * {@code deliveryService.deliver} opens after {@link TenantContext} is set,
 * so {@code @TenantId} resolves correctly the first time.
 *
 * Receives the raw JSON body as a String and parses it by hand, the
 * receiving half of the same workaround {@code SqsNotificationPublisher}
 * documents: Spring Cloud AWS's automatic record conversion is not
 * reliable, and the publisher already sends pre-serialized JSON rather
 * than a {@code NotificationMessage} object, so this must undo that
 * symmetrically instead of asking the framework to convert the type again.
 *
 * Any exception from parsing or delivery propagates out of this method.
 * Spring Cloud AWS then leaves the message unacknowledged: SQS redelivers
 * it after the queue's visibility timeout, and the redrive policy in
 * elasticmq.conf moves it to notifications-dlq after too many failures.
 * There is deliberately no try/catch here that would swallow a failure and
 * acknowledge a message that was never actually delivered.
 */
@Component
public class SqsNotificationListener {

    private final NotificationDeliveryService deliveryService;
    private final ObjectMapper objectMapper;

    public SqsNotificationListener(NotificationDeliveryService deliveryService, ObjectMapper objectMapper) {
        this.deliveryService = deliveryService;
        this.objectMapper = objectMapper;
    }

    @SqsListener("${notification.sqs.queue}")
    public void onMessage(String body) throws JsonProcessingException {
        NotificationMessage message = objectMapper.readValue(body, NotificationMessage.class);
        TenantContext.runAs(message.tenantId(), () -> deliveryService.deliver(message.notificationId()));
    }
}
