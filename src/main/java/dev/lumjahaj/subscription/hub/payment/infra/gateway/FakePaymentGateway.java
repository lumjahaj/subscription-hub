package dev.lumjahaj.subscription.hub.payment.infra.gateway;

import dev.lumjahaj.subscription.hub.payment.domain.PaymentEvent;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentEventHandler;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentGateway;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentGatewayException;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentLookup;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * The default provider: no network, no account, no secrets, so the app runs
 * and the test suite passes from a clean clone (CLAUDE.md §2).
 *
 * It imitates Stripe's shape rather than just answering "succeeded":
 * creating a payment returns a reference, and the outcome is delivered
 * separately as a {@link PaymentEvent} through the same handler a real
 * webhook uses. Otherwise the default path and the real one would settle
 * payments differently, and only one of them would ever be tested.
 *
 * Outcomes are deterministic from the payment method, and the method names
 * are Stripe's own test PaymentMethod ids, so the same requests work
 * unchanged against either provider:
 * <ul>
 *   <li>{@code pm_card_visa} — succeeds</li>
 *   <li>{@code pm_card_visa_chargeDeclined} — fails with {@code card_declined}</li>
 *   <li>{@code pm_card_visa_chargeDeclinedInsufficientFunds} — fails with {@code insufficient_funds}</li>
 *   <li>{@code pm_fake_provider_unavailable} — fake-only: the provider can't be reached</li>
 *   <li>anything else — fails with {@code payment_method_invalid}</li>
 * </ul>
 *
 * The event is delivered synchronously, before this method returns. That
 * keeps tests deterministic, and it happens to exercise the awkward
 * real-world ordering on every call: a webhook arriving before the create
 * call's response has been recorded.
 */
@Component
@ConditionalOnProperty(name = "payment.provider", havingValue = "fake", matchIfMissing = true)
public class FakePaymentGateway implements PaymentGateway {

    static final String PROVIDER = "fake";

    static final String SUCCEEDS = "pm_card_visa";
    static final String DECLINED = "pm_card_visa_chargeDeclined";
    static final String INSUFFICIENT_FUNDS = "pm_card_visa_chargeDeclinedInsufficientFunds";
    static final String PROVIDER_UNAVAILABLE = "pm_fake_provider_unavailable";

    private final PaymentEventHandler events;

    public FakePaymentGateway(PaymentEventHandler events) {
        this.events = events;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public String createPayment(PaymentRequest request) {
        if (PROVIDER_UNAVAILABLE.equals(request.paymentMethod())) {
            throw new PaymentGatewayException("Fake payment provider is unavailable");
        }

        String compactId = compactId(request.paymentId());
        events.handle(eventFor(request, referenceFor(compactId), "fake_evt_" + compactId));
        return referenceFor(compactId);
    }

    /**
     * The fake's provider-side record is a pure function of the request, so
     * it can answer this without storing anything — the same trick that makes
     * its references and outcomes deterministic.
     *
     * A reference means the create call landed, so the outcome is whatever
     * the payment method says it is. No reference means it never returned,
     * and the honest imitation of a real provider is to re-run the create
     * path: unavailable stays unavailable, and anything else yields the same
     * reference the first call would have, because the payment id is the
     * idempotency key.
     *
     * The event is returned rather than delivered through the handler the way
     * {@link #createPayment} delivers it: reconciliation hands it to
     * settlement itself, and a gateway that settled on the side would be a
     * second path into the money.
     */
    @Override
    public Optional<PaymentEvent> reconcile(PaymentLookup lookup) {
        PaymentRequest request = lookup.request();
        String reference = lookup.providerReference();
        if (reference == null) {
            if (PROVIDER_UNAVAILABLE.equals(request.paymentMethod())) {
                throw new PaymentGatewayException("Fake payment provider is unavailable");
            }
            reference = referenceFor(compactId(request.paymentId()));
        }
        return Optional.of(eventFor(
                request, reference, "fake_reconcile_evt_" + compactId(request.paymentId())));
    }

    private static String compactId(UUID paymentId) {
        return paymentId.toString().replace("-", "");
    }

    /**
     * Derived from the payment id, which is also the idempotency key:
     * submitting the same payment twice yields the same reference, as a real
     * provider honouring the key would.
     */
    private static String referenceFor(String compactId) {
        return "fake_pi_" + compactId;
    }

    private static PaymentEvent eventFor(PaymentRequest request, String reference, String eventId) {
        return switch (request.paymentMethod()) {
            case SUCCEEDS -> event(request, reference, eventId, PaymentEvent.Outcome.SUCCEEDED, null);
            case DECLINED -> event(request, reference, eventId, PaymentEvent.Outcome.FAILED, "card_declined");
            case INSUFFICIENT_FUNDS -> event(request, reference, eventId, PaymentEvent.Outcome.FAILED, "insufficient_funds");
            default -> event(request, reference, eventId, PaymentEvent.Outcome.FAILED, "payment_method_invalid");
        };
    }

    private static PaymentEvent event(PaymentRequest request, String reference, String eventId,
                                      PaymentEvent.Outcome outcome, String failureCode) {
        return new PaymentEvent(eventId, PROVIDER, request.tenantId(), request.paymentId(),
                reference, outcome, failureCode);
    }
}
