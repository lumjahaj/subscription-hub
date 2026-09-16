package dev.lumjahaj.subscription.hub.dunning.app;

import dev.lumjahaj.subscription.hub.billing.domain.InvoiceRepository;
import dev.lumjahaj.subscription.hub.billing.domain.InvoiceStatus;
import dev.lumjahaj.subscription.hub.billing.infra.jpa.InvoiceEntity;
import dev.lumjahaj.subscription.hub.common.logging.MdcKeys;
import dev.lumjahaj.subscription.hub.payment.app.PaymentService;
import dev.lumjahaj.subscription.hub.tenancy.domain.Tenant;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Tries to collect invoices that are still unpaid.
 *
 * Deliberately a separate job from BillingCycleJob rather than another step
 * inside it. That job's invoice-then-renew ordering is a documented
 * invariant (CLAUDE.md §5), and collection cannot join it: settlement is
 * asynchronous, so "charge, then decide whether to renew" would depend on a
 * webhook that arrives long after the run has finished. This job reacts to
 * *state* — an invoice that is still OPEN — and leaves what an outcome
 * means to DunningService.
 *
 * Thin trigger only, like BillingCycleJob: find the work, call a
 * transactional service, never decide anything itself. In particular it
 * never reads whether an attempt succeeded — it cannot, because with a real
 * provider nothing is known yet when this returns.
 */
@Component
public class DunningJob {

    private static final Logger log = LoggerFactory.getLogger(DunningJob.class);

    private final TenantRepository tenants;
    private final InvoiceRepository invoices;
    private final DunningService dunningService;
    private final PaymentService paymentService;

    public DunningJob(
            TenantRepository tenants,
            InvoiceRepository invoices,
            DunningService dunningService,
            PaymentService paymentService
    ) {
        this.tenants = tenants;
        this.invoices = invoices;
        this.dunningService = dunningService;
        this.paymentService = paymentService;
    }

    @Scheduled(cron = "${dunning.cycle.cron}")
    public void run() {
        Instant now = Instant.now();
        for (Tenant tenant : tenants.findAllActive()) {
            TenantContext.runAs(tenant.id(), () -> processTenant(tenant.id(), now));
        }
    }

    private void processTenant(String tenantId, Instant now) {
        MDC.put(MdcKeys.TENANT_ID, tenantId);
        try {
            for (InvoiceEntity invoice : invoices.findByTenantIdAndStatus(tenantId, InvoiceStatus.OPEN)) {
                UUID invoiceId = invoice.getId();
                try {
                    collect(tenantId, invoiceId, now);
                } catch (Exception ex) {
                    // One uncollectable invoice must not end the tenant's run.
                    log.error("Dunning failed for invoice {}", invoiceId, ex);
                }
            }
        } finally {
            MDC.remove(MdcKeys.TENANT_ID);
        }
    }

    /**
     * The attempt is claimed and committed first, then the provider is
     * called with no transaction open — the same split PaymentService uses,
     * for the same reason (CLAUDE.md §5).
     */
    private void collect(String tenantId, UUID invoiceId, Instant now) {
        dunningService.startAttempt(tenantId, invoiceId, now).ifPresent(attempt -> {
            try {
                paymentService.pay(invoiceId, attempt.paymentMethod(), attempt.idempotencyKey());
            } catch (Exception ex) {
                // A decline does not land here: it settles the payment as
                // FAILED and DunningService reacts to that. Reaching here
                // means the attempt could not be made at all — the provider
                // was unreachable, or another payment is in flight — and the
                // schedule has already moved the invoice to its next slot.
                log.warn("Dunning attempt {} for invoice {} could not be made: {}",
                        attempt.number(), invoiceId, ex.toString());
            }
        });
    }
}
