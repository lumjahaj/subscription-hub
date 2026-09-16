package dev.lumjahaj.subscription.hub.billing.app;

import dev.lumjahaj.subscription.hub.billing.domain.InvoiceIssuedListener;
import dev.lumjahaj.subscription.hub.billing.domain.InvoiceRepository;
import dev.lumjahaj.subscription.hub.billing.domain.InvoiceStatus;
import dev.lumjahaj.subscription.hub.billing.infra.jpa.InvoiceEntity;
import dev.lumjahaj.subscription.hub.billing.infra.jpa.InvoiceLineEntity;
import dev.lumjahaj.subscription.hub.catalog.infra.jpa.PlanEntity;
import dev.lumjahaj.subscription.hub.common.api.ResourceAlreadyExistsException;
import dev.lumjahaj.subscription.hub.common.api.ResourceNotFoundException;
import dev.lumjahaj.subscription.hub.subscription.domain.SubscriptionRepository;
import dev.lumjahaj.subscription.hub.subscription.infra.jpa.SubscriptionEntity;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import dev.lumjahaj.subscription.hub.usage.domain.UsageCounterRepository;
import dev.lumjahaj.subscription.hub.usage.infra.jpa.UsageCounterEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class InvoiceService {

    private final InvoiceRepository invoices;
    private final SubscriptionRepository subscriptions;
    private final UsageCounterRepository usageCounters;
    private final MeterPriceResolver meterPrices;
    private final int dueDays;
    private final List<InvoiceIssuedListener> listeners;

    /**
     * The listener list is injected rather than a single collaborator, and
     * may be empty - the same shape PaymentSettlementService uses for
     * PaymentOutcomeListener. Billing works on its own; notification is one
     * optional reaction to an invoice existing, not part of generating one.
     */
    public InvoiceService(
            InvoiceRepository invoices,
            SubscriptionRepository subscriptions,
            UsageCounterRepository usageCounters,
            MeterPriceResolver meterPrices,
            @Value("${billing.invoice.due-days}") int dueDays,
            List<InvoiceIssuedListener> listeners
    ) {
        this.invoices = invoices;
        this.subscriptions = subscriptions;
        this.usageCounters = usageCounters;
        this.meterPrices = meterPrices;
        this.dueDays = dueDays;
        this.listeners = listeners;
    }

    /**
     * Reloads the subscription inside this transaction rather than
     * accepting an already-loaded entity, the same reason
     * SubscriptionRenewalService.renewIfDue does - the lazy plan/customer
     * associations must still be fetchable when a cron job calls this
     * with no open-session-in-view.
     *
     * The invoice covers exactly [currentPeriodStart, currentPeriodEnd) -
     * the same two fields UsageService.record keys its counters by, so
     * the usage join below is exact by construction rather than by
     * reconstructing a closed period's boundaries after renewal has
     * already overwritten them.
     */
    @Transactional
    public InvoiceEntity generateForCurrentPeriod(UUID subscriptionId) {
        String tenantId = TenantContext.getTenantId();
        SubscriptionEntity subscription = subscriptions.findByTenantIdAndId(tenantId, subscriptionId)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription", subscriptionId.toString()));

        Instant now = Instant.now();
        Instant periodStart = subscription.getCurrentPeriodStart();
        Instant periodEnd = subscription.getCurrentPeriodEnd();
        if (now.isBefore(periodEnd)) {
            throw new PeriodNotClosedException(periodEnd);
        }

        invoices.findByTenantIdAndSubscriptionIdAndPeriodStart(tenantId, subscriptionId, periodStart)
                .ifPresent(existing -> {
                    throw new ResourceAlreadyExistsException("Invoice", existing.getNumber());
                });

        PlanEntity plan = subscription.getPlan();
        Map<String, MeterPrice> prices = meterPrices.forPlan(tenantId, plan.getId());
        List<UsageCounterEntity> counters =
                usageCounters.findByTenantIdAndSubscriptionIdAndPeriodStart(tenantId, subscriptionId, periodStart);

        List<InvoiceLineEntity> lines = InvoiceCalculator.calculateLines(subscription, counters, prices);

        InvoiceEntity invoice = new InvoiceEntity();
        invoice.setTenantId(tenantId);
        invoice.setSubscription(subscription);
        invoice.setCustomer(subscription.getCustomer());
        invoice.setNumber(formatNumber(invoices.allocateNextNumber(tenantId)));
        invoice.setStatus(InvoiceStatus.OPEN);
        invoice.setCurrency(plan.getCurrency());
        invoice.setPeriodStart(periodStart);
        invoice.setPeriodEnd(periodEnd);
        invoice.setIssuedAt(now);
        invoice.setDueAt(now.plus(Duration.ofDays(dueDays)));
        invoice.setTotalCents(InvoiceCalculator.totalCents(lines));
        lines.forEach(invoice::addLine);

        InvoiceEntity saved = invoices.save(invoice);
        // Inside this transaction, like PaymentSettlementService's
        // notifyListeners: a listener that enqueues an outbox row and then
        // rolls back must take the invoice down with it, not leave an
        // email queued for an invoice that was never actually created.
        listeners.forEach(listener -> listener.onInvoiceIssued(saved.getId()));
        return saved;
    }

    public InvoiceEntity getById(UUID id) {
        String tenantId = TenantContext.getTenantId();
        return invoices.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice", id.toString()));
    }

    public Page<InvoiceEntity> list(Pageable pageable) {
        String tenantId = TenantContext.getTenantId();
        return invoices.findByTenantId(tenantId, pageable);
    }

    public Page<InvoiceEntity> listBySubscription(UUID subscriptionId, Pageable pageable) {
        String tenantId = TenantContext.getTenantId();
        return invoices.findByTenantIdAndSubscriptionId(tenantId, subscriptionId, pageable);
    }

    // INV-000001 - fits varchar(32) with room for a future year segment.
    // Numbers are per-tenant (the sequence row is keyed by tenant) and
    // gap-free (the allocation rolls back with the transaction that
    // failed - see the V6 migration comment on invoice_number_sequence).
    private static String formatNumber(long n) {
        return String.format("INV-%06d", n);
    }
}
