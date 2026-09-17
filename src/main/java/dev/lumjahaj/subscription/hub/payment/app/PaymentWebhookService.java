package dev.lumjahaj.subscription.hub.payment.app;

import dev.lumjahaj.subscription.hub.payment.domain.PaymentEvent;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentEventHandler;
import dev.lumjahaj.subscription.hub.payment.domain.ProviderWebhook;
import dev.lumjahaj.subscription.hub.payment.domain.WebhookVerificationException;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

/**
 * Entry point for provider webhooks: verify, then settle.
 *
 * Settlement itself is the same PaymentSettlementService the fake gateway
 * drives — this class only establishes the one thing a webhook request
 * lacks and an API request has: a tenant.
 */
@Service
public class PaymentWebhookService {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookService.class);

    private final ProviderWebhook webhook;
    private final PaymentEventHandler settlement;
    private final TransactionTemplate transaction;

    public PaymentWebhookService(
            ProviderWebhook webhook,
            PaymentEventHandler settlement,
            PlatformTransactionManager transactionManager
    ) {
        this.webhook = webhook;
        this.settlement = settlement;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public void handle(String payload, String signature) {
        Optional<PaymentEvent> event;
        try {
            event = webhook.parse(payload, signature);
        } catch (WebhookVerificationException ex) {
            log.warn("Rejected a webhook request: {}", ex.getMessage());
            throw new InvalidWebhookSignatureException();
        }

        // Nothing to act on (an event type we don't handle, or a payment
        // this application didn't create). The caller answers 200: a
        // provider retries anything else for days.
        event.ifPresent(this::settle);
    }

    /**
     * The tenant comes from the event, but only after its signature
     * verified — the provider vouches for metadata we ourselves attached.
     * Settlement then still loads the payment with findByTenantIdAndId, so
     * a mis-tagged event finds nothing rather than touching another
     * tenant's row.
     *
     * <p>The tenant is set before the transaction starts because Hibernate
     * resolves {@code @TenantId} when a session opens, and the session opens
     * with the transaction. This used to need more: with
     * {@code spring.jpa.open-in-view} on, the request had already opened an
     * EntityManager - pinned to the {@code __no_tenant__} sentinel, since a
     * webhook carries no token - and every transaction adopted it, so this
     * method had to unbind it and rebind it afterwards. (PROPAGATION_REQUIRES_NEW
     * did not help: with no transaction active there was nothing to suspend.)
     * Open-in-view is off now, nothing is bound to the request, and a
     * transaction opened inside runAs gets a session scoped to the right tenant.
     */
    private void settle(PaymentEvent event) {
        TenantContext.runAs(event.tenantId(),
                () -> transaction.executeWithoutResult(status -> settlement.handle(event)));
    }
}
