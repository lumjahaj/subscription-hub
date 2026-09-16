package dev.lumjahaj.subscription.hub.payment.infra.gateway;

import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeError;
import com.stripe.model.StripeObject;
import com.stripe.net.Webhook;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentEvent;
import dev.lumjahaj.subscription.hub.payment.domain.ProviderWebhook;
import dev.lumjahaj.subscription.hub.payment.domain.WebhookVerificationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Verifies Stripe's signature and translates its events into
 * {@link PaymentEvent}.
 *
 * Not conditional on {@code payment.provider}, unlike
 * StripePaymentGateway: verification needs only the signing secret, never
 * an API key or a network call, and keeping the endpoint always present
 * means the webhook path is covered by the test suite in the same Spring
 * context as everything else (CLAUDE.md §5 — a differing property set would
 * fork the context cache). A Stripe event still can't settle a fake
 * payment: PaymentSettlementService compares the event's provider with the
 * payment's.
 *
 * Only two event types matter. Everything else — and any PaymentIntent
 * without this application's metadata — is ignored rather than rejected,
 * because Stripe retries anything that isn't 2xx for three days.
 */
@Component
public class StripeWebhook implements ProviderWebhook {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhook.class);

    private static final String SUCCEEDED_EVENT = "payment_intent.succeeded";
    private static final String FAILED_EVENT = "payment_intent.payment_failed";

    private final String webhookSecret;

    public StripeWebhook(@Value("${stripe.webhook-secret:}") String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    @Override
    public Optional<PaymentEvent> parse(String payload, String signature) {
        Event event = verify(payload, signature);

        if (!SUCCEEDED_EVENT.equals(event.getType()) && !FAILED_EVENT.equals(event.getType())) {
            log.debug("Ignoring Stripe event {} of type {}", event.getId(), event.getType());
            return Optional.empty();
        }

        PaymentIntent intent = paymentIntentOf(event);
        Map<String, String> metadata = intent.getMetadata() == null ? Map.of() : intent.getMetadata();
        String tenantId = metadata.get(StripePaymentGateway.TENANT_METADATA_KEY);
        String paymentId = metadata.get(StripePaymentGateway.PAYMENT_METADATA_KEY);
        if (tenantId == null || paymentId == null) {
            // A PaymentIntent created by something other than this
            // application (the dashboard, another service sharing the
            // account). Nothing here to settle.
            log.warn("Ignoring Stripe event {}: PaymentIntent {} carries no tenant/payment metadata",
                    event.getId(), intent.getId());
            return Optional.empty();
        }

        return Optional.of(new PaymentEvent(
                event.getId(),
                StripePaymentGateway.PROVIDER,
                tenantId,
                parsePaymentId(paymentId, event.getId()),
                intent.getId(),
                SUCCEEDED_EVENT.equals(event.getType())
                        ? PaymentEvent.Outcome.SUCCEEDED
                        : PaymentEvent.Outcome.FAILED,
                failureCodeOf(intent)));
    }

    private Event verify(String payload, String signature) {
        if (webhookSecret.isBlank()) {
            // Nothing can be verified, so nothing may be acted on. Refusing
            // outright beats a configuration mistake silently accepting
            // unsigned requests on a public endpoint.
            throw new WebhookVerificationException("stripe.webhook-secret is not configured");
        }
        if (signature == null || signature.isBlank()) {
            throw new WebhookVerificationException("Stripe-Signature header is missing");
        }
        try {
            // Also enforces Stripe's 5-minute timestamp tolerance, which is
            // what stops a captured payload being replayed later.
            return Webhook.constructEvent(payload, signature, webhookSecret);
        } catch (SignatureVerificationException ex) {
            throw new WebhookVerificationException("Stripe signature verification failed", ex);
        } catch (RuntimeException ex) {
            // Malformed JSON, or a secret the SDK rejects outright.
            throw new WebhookVerificationException("Stripe webhook payload could not be read", ex);
        }
    }

    /**
     * The typed object is only returned when the event's API version
     * matches the one this SDK was built for, which it eventually won't be.
     * deserializeUnsafe() reads it anyway — "unsafe" meaning fields may be
     * missing or renamed, which is acceptable for the three fields used
     * here (id, metadata, last_payment_error) since they are among the
     * oldest in the API.
     */
    private PaymentIntent paymentIntentOf(Event event) {
        StripeObject object = event.getDataObjectDeserializer().getObject().orElseGet(() -> {
            try {
                return event.getDataObjectDeserializer().deserializeUnsafe();
            } catch (EventDataObjectDeserializationException ex) {
                throw new WebhookVerificationException(
                        "Stripe event " + event.getId() + " could not be deserialized", ex);
            }
        });
        if (!(object instanceof PaymentIntent intent)) {
            throw new WebhookVerificationException(
                    "Stripe event " + event.getId() + " does not carry a PaymentIntent");
        }
        return intent;
    }

    private static UUID parsePaymentId(String paymentId, String eventId) {
        try {
            return UUID.fromString(paymentId);
        } catch (IllegalArgumentException ex) {
            throw new WebhookVerificationException(
                    "Stripe event " + eventId + " carries an unparseable payment_id", ex);
        }
    }

    /**
     * decline_code is the specific reason ("insufficient_funds") and code
     * the general one ("card_declined"); prefer the specific, matching what
     * the fake gateway reports.
     */
    private static String failureCodeOf(PaymentIntent intent) {
        StripeError error = intent.getLastPaymentError();
        if (error == null) {
            return null;
        }
        return error.getDeclineCode() != null ? error.getDeclineCode() : error.getCode();
    }
}
