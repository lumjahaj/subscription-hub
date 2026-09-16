package dev.lumjahaj.subscription.hub.payment.infra.gateway;

import com.stripe.StripeClient;
import com.stripe.exception.CardException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentGateway;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentGatewayException;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * The real provider, selected with {@code payment.provider=stripe}.
 *
 * Creates and confirms a PaymentIntent in one call, then returns its id and
 * stops. It never reports the outcome: that arrives as a webhook, which
 * StripeWebhook translates into the same PaymentEvent the fake produces.
 * The whole port was shaped this way so this class could be added without
 * touching PaymentService or PaymentSettlementService.
 */
@Component
@ConditionalOnProperty(name = "payment.provider", havingValue = "stripe")
public class StripePaymentGateway implements PaymentGateway {

    static final String PROVIDER = "stripe";

    /** Read back off the event's metadata by StripeWebhook — they must agree. */
    static final String TENANT_METADATA_KEY = "tenant_id";
    static final String PAYMENT_METADATA_KEY = "payment_id";

    private static final Logger log = LoggerFactory.getLogger(StripePaymentGateway.class);

    private final StripeClient stripe;

    public StripePaymentGateway(StripeClient stripe) {
        this.stripe = stripe;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public String createPayment(PaymentRequest request) {
        RequestOptions options = RequestOptions.builder()
                // Our payment id. Resubmitting after a timeout returns the
                // PaymentIntent Stripe already created instead of charging
                // the customer a second time.
                .setIdempotencyKey(request.paymentId().toString())
                .build();

        try {
            PaymentIntent intent = stripe.paymentIntents().create(params(request), options);
            return intent.getId();
        } catch (CardException declined) {
            // A decline is not a failure to reach Stripe: the PaymentIntent
            // exists and Stripe will send payment_intent.payment_failed for
            // it. Returning its id records the reference and lets the
            // webhook settle the payment FAILED, exactly as the fake does.
            // Treating this as a gateway error instead would leave the
            // payment PENDING and block the invoice.
            String intentId = intentIdOf(declined);
            if (intentId == null) {
                throw new PaymentGatewayException("Stripe declined payment "
                        + request.paymentId() + " without a PaymentIntent", declined);
            }
            log.info("Stripe declined payment {} ({}); awaiting the webhook",
                    request.paymentId(), declined.getCode());
            return intentId;
        } catch (StripeException ex) {
            // Connection, rate-limit, authentication or API errors. The
            // outcome is genuinely unknown (a timeout can happen after the
            // charge), so PaymentService leaves the payment PENDING and the
            // same Idempotency-Key can resume it.
            throw new PaymentGatewayException(
                    "Stripe could not be reached for payment " + request.paymentId(), ex);
        }
    }

    private static PaymentIntentCreateParams params(PaymentRequest request) {
        return PaymentIntentCreateParams.builder()
                .setAmount(request.amountCents())
                // Stripe wants a lowercase ISO code; plans store "USD".
                .setCurrency(request.currency().toLowerCase(Locale.ROOT))
                .setPaymentMethod(request.paymentMethod())
                // Create and confirm in one call - there is no client-side
                // step to wait for.
                .setConfirm(true)
                // The customer is not in a checkout flow: this is a backend
                // charging a stored method, so nobody can answer a prompt.
                .setOffSession(true)
                // Consequently, fail rather than park the payment in
                // requires_action: there is no UI to complete a 3-D Secure
                // challenge, and a payment nobody can finish would hold the
                // invoice's in-flight slot indefinitely.
                .setErrorOnRequiresAction(true)
                // Without allow_redirects=never, Stripe expects a return_url
                // for redirect-based methods, which a server-to-server
                // charge has nowhere to send anyone.
                .setAutomaticPaymentMethods(
                        PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                .setEnabled(true)
                                .setAllowRedirects(
                                        PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER)
                                .build())
                // How a webhook finds its way back to our row. The tenant
                // travels too, because a webhook request carries no token
                // and therefore no tenant context.
                .putMetadata(TENANT_METADATA_KEY, request.tenantId())
                .putMetadata(PAYMENT_METADATA_KEY, request.paymentId().toString())
                .build();
    }

    private static String intentIdOf(CardException declined) {
        if (declined.getStripeError() == null || declined.getStripeError().getPaymentIntent() == null) {
            return null;
        }
        return declined.getStripeError().getPaymentIntent().getId();
    }
}
