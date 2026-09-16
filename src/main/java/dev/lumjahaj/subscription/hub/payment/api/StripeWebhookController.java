package dev.lumjahaj.subscription.hub.payment.api;

import dev.lumjahaj.subscription.hub.payment.app.PaymentWebhookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where Stripe reports what happened to a payment.
 *
 * Public (see SecurityConfig): the caller is Stripe, which holds no token.
 * The signature check in StripeWebhook is what authenticates the request,
 * and it is the only reason anything here is trusted.
 *
 * The body is taken as a raw String, never a parsed DTO — the signature
 * covers the exact bytes sent, so letting Jackson parse and re-serialize
 * would break verification. This controller knows nothing about Stripe's
 * format beyond the header name; the SDK stays behind the ProviderWebhook
 * port in infra/gateway.
 */
@RestController
@RequestMapping("/api/webhooks/stripe")
public class StripeWebhookController {

    private static final String SIGNATURE_HEADER = "Stripe-Signature";

    private final PaymentWebhookService webhookService;

    public StripeWebhookController(PaymentWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    /**
     * 200 for anything handled or deliberately ignored; 400 only when the
     * signature can't be verified. A provider retries every non-2xx for
     * days, so an unknown event type must not look like a failure.
     *
     * Settlement runs before the response, rather than being queued: at
     * this volume the work is one short transaction, and returning 200
     * before doing it would throw away the retry that makes webhooks
     * reliable in the first place.
     */
    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestBody String payload,
            @RequestHeader(name = SIGNATURE_HEADER, required = false) String signature
    ) {
        webhookService.handle(payload, signature);
        return ResponseEntity.ok().build();
    }
}
