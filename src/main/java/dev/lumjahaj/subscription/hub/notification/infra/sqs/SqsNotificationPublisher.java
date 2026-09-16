package dev.lumjahaj.subscription.hub.notification.infra.sqs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.lumjahaj.subscription.hub.notification.domain.NotificationMessage;
import dev.lumjahaj.subscription.hub.notification.domain.NotificationPublisher;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Publishes to the notification queue. ElasticMQ stands in for real SQS
 * locally and in tests; only the endpoint in application.yml changes to
 * point this at production SQS.
 *
 * The payload is serialized to a JSON string by hand rather than handed to
 * {@code SqsTemplate} as the {@code NotificationMessage} record itself.
 * Spring Cloud AWS's default payload conversion does not reliably serialize
 * a Java record — observed against the real ElasticMQ container as the
 * record's {@code toString()} landing on the queue verbatim, which then
 * fails JSON parsing on the receiving side. Sending an already-serialized
 * String sidesteps that conversion path entirely: {@code StringMessageConverter}
 * passes a String payload through unchanged.
 */
@Component
public class SqsNotificationPublisher implements NotificationPublisher {

    private final SqsTemplate sqsTemplate;
    private final ObjectMapper objectMapper;
    private final String queueName;

    public SqsNotificationPublisher(
            SqsTemplate sqsTemplate, ObjectMapper objectMapper, @Value("${notification.sqs.queue}") String queueName) {
        this.sqsTemplate = sqsTemplate;
        this.objectMapper = objectMapper;
        this.queueName = queueName;
    }

    @Override
    public void publish(NotificationMessage message) {
        String json;
        try {
            json = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            // NotificationMessage is two plain fields (a String and a UUID) -
            // this cannot realistically fail; wrapping keeps a checked
            // exception out of the port's signature.
            throw new IllegalStateException("Failed to serialize notification message " + message, e);
        }
        sqsTemplate.send(to -> to.queue(queueName).payload(json));
    }
}
