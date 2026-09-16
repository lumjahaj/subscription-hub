package dev.lumjahaj.subscription.hub.payment.app;

import dev.lumjahaj.subscription.hub.payment.domain.PaymentEvent;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentEventHandler;
import dev.lumjahaj.subscription.hub.payment.domain.ProviderWebhook;
import dev.lumjahaj.subscription.hub.payment.domain.WebhookVerificationException;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

/**
 * Entry point for provider webhooks: verify, then settle.
 *
 * Settlement itself is the same PaymentSettlementService the fake gateway
 * drives — this class only establishes the two things a webhook request
 * lacks and an API request has: a tenant, and a Hibernate session that
 * knows about it.
 */
@Service
public class PaymentWebhookService {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookService.class);

    private final ProviderWebhook webhook;
    private final PaymentEventHandler settlement;
    private final EntityManagerFactory entityManagerFactory;
    private final TransactionTemplate transaction;

    public PaymentWebhookService(
            ProviderWebhook webhook,
            PaymentEventHandler settlement,
            EntityManagerFactory entityManagerFactory,
            PlatformTransactionManager transactionManager
    ) {
        this.webhook = webhook;
        this.settlement = settlement;
        this.entityManagerFactory = entityManagerFactory;
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
     * <p><b>Why the EntityManager is detached first.</b> A webhook request
     * carries no token, so TenantContext is empty when
     * {@code spring.jpa.open-in-view} opens the request's EntityManager —
     * and Hibernate resolves {@code @TenantId} when a session opens, not
     * when a query runs. Every query on that session is therefore pinned to
     * TenantIdentifierResolver's {@code __no_tenant__} sentinel and matches
     * no row: the same chicken-and-egg documented for AppUserEntity
     * (CLAUDE.md §4), arriving from the other direction.
     *
     * <p>PROPAGATION_REQUIRES_NEW does <em>not</em> fix it, which is worth
     * knowing: suspension only happens when a transaction is already
     * active, and open-in-view binds an EntityManager without one — so
     * JpaTransactionManager simply adopts the bound EntityManager and its
     * sentinel tenant. Unbinding it for the duration is what forces a
     * genuinely new session, opened after the tenant is set. It is rebound
     * afterwards so open-in-view still closes it at the end of the request.
     */
    private void settle(PaymentEvent event) {
        Object requestScopedEntityManager =
                TransactionSynchronizationManager.unbindResourceIfPossible(entityManagerFactory);
        try {
            TenantContext.runAs(event.tenantId(),
                    () -> transaction.executeWithoutResult(status -> settlement.handle(event)));
        } finally {
            if (requestScopedEntityManager != null) {
                TransactionSynchronizationManager.bindResource(entityManagerFactory, requestScopedEntityManager);
            }
        }
    }
}
