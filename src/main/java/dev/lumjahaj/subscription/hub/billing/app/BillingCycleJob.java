package dev.lumjahaj.subscription.hub.billing.app;

import dev.lumjahaj.subscription.hub.common.api.ResourceAlreadyExistsException;
import dev.lumjahaj.subscription.hub.common.metrics.JobMetrics;
import dev.lumjahaj.subscription.hub.common.logging.MdcKeys;
import dev.lumjahaj.subscription.hub.subscription.app.SubscriptionRenewalService;
import dev.lumjahaj.subscription.hub.subscription.domain.SubscriptionRepository;
import dev.lumjahaj.subscription.hub.subscription.domain.SubscriptionStatus;
import dev.lumjahaj.subscription.hub.subscription.infra.jpa.SubscriptionEntity;
import dev.lumjahaj.subscription.hub.tenancy.domain.Tenant;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Replaces RenewalJob. Invoicing and renewal are two steps of one
 * process, not two independent jobs: once a renewal advances a
 * subscription, the closed period's start is no longer recoverable from
 * the row (see InvoiceService), so running the two on separate hourly
 * crons would race, and the losing side is unbilled revenue. This job
 * invoices each due subscription, renders its PDF, then renews it, in
 * that order, per subscription — same "thin trigger only" shape
 * RenewalJob had.
 *
 * Reuses the existing "due for renewal" query unchanged rather than
 * adding a new finder: nextRenewal == currentPeriodEnd at every write
 * site (SubscriptionService.create, both branches, and
 * SubscriptionJpaRepository.renewIfCurrent), so a subscription this query
 * selects always has a closed current period, which is exactly what
 * InvoiceService.generateForCurrentPeriod needs to succeed. If those two
 * ever diverge, billing will need its own finder.
 */
@Component
public class BillingCycleJob {

    private static final Logger log = LoggerFactory.getLogger(BillingCycleJob.class);
    private static final String JOB = "billing-cycle";
    private static final Set<SubscriptionStatus> DUE_STATUSES =
            EnumSet.of(SubscriptionStatus.TRIALING, SubscriptionStatus.ACTIVE);

    private final TenantRepository tenants;
    private final SubscriptionRepository subscriptions;
    private final InvoiceService invoiceService;
    private final InvoicePdfService invoicePdfService;
    private final SubscriptionRenewalService renewalService;
    private final JobMetrics jobMetrics;

    public BillingCycleJob(
            TenantRepository tenants,
            SubscriptionRepository subscriptions,
            InvoiceService invoiceService,
            InvoicePdfService invoicePdfService,
            SubscriptionRenewalService renewalService,
            JobMetrics jobMetrics
    ) {
        this.tenants = tenants;
        this.subscriptions = subscriptions;
        this.invoiceService = invoiceService;
        this.invoicePdfService = invoicePdfService;
        this.renewalService = renewalService;
        this.jobMetrics = jobMetrics;
    }

    @Scheduled(cron = "${billing.cycle.cron}")
    public void run() {
        jobMetrics.run(JOB, () -> {
            Instant now = Instant.now();
            for (Tenant tenant : tenants.findAllActive()) {
                TenantContext.runAs(tenant.id(), () -> processTenant(tenant.id(), now));
            }
        });
    }

    private void processTenant(String tenantId, Instant now) {
        MDC.put(MdcKeys.TENANT_ID, tenantId);
        try {
            List<SubscriptionEntity> due = subscriptions.findByTenantIdAndStatusInAndNextRenewalLessThanEqual(
                    tenantId, DUE_STATUSES, now);
            for (SubscriptionEntity subscription : due) {
                processOneSafely(subscription.getId(), now);
            }
        } finally {
            MDC.remove(MdcKeys.TENANT_ID);
        }
    }

    /**
     * Invoice, then render its PDF, then renew — in that order, each in
     * its own transaction (see InvoiceService and InvoicePdfService), so
     * one bad subscription rolls back only itself.
     *
     * The three steps fail differently on purpose:
     *
     * - Invoicing already done (ResourceAlreadyExistsException) means a
     *   previous run got this far before a later failure, or a manual
     *   POST beat the job to it. Done, not broken, so the cycle
     *   continues; there is simply no new invoice to render.
     * - Invoicing failed for any other reason means the period is
     *   genuinely unbilled. Renewing past it would be exactly the revenue
     *   loss this ordering exists to prevent, so the subscription is left
     *   due and retried next run rather than renewed.
     * - The PDF failing blocks nothing. Unlike a missed invoice, a
     *   missing PDF costs nothing that can't be recovered later by
     *   POST /api/invoices/{id}/pdf, so an object-store outage must not
     *   be allowed to stall billing.
     */
    private void processOneSafely(UUID subscriptionId, Instant now) {
        UUID invoiceId;
        try {
            invoiceId = invoiceService.generateForCurrentPeriod(subscriptionId).getId();
        } catch (ResourceAlreadyExistsException alreadyInvoiced) {
            invoiceId = null;
        } catch (Exception ex) {
            log.error("Invoicing failed for subscription {}", subscriptionId, ex);
            jobMetrics.itemFailed(JOB, "invoice");
            return;
        }

        if (invoiceId != null) {
            generatePdfSafely(invoiceId);
        }
        renewOneSafely(subscriptionId, now);
    }

    private void generatePdfSafely(UUID invoiceId) {
        try {
            invoicePdfService.generatePdf(invoiceId);
        } catch (Exception ex) {
            log.error("PDF generation failed for invoice {}", invoiceId, ex);
            jobMetrics.itemFailed(JOB, "pdf");
        }
    }

    private void renewOneSafely(UUID subscriptionId, Instant now) {
        try {
            renewalService.renewIfDue(subscriptionId, now);
        } catch (Exception ex) {
            log.error("Renewal failed for subscription {}", subscriptionId, ex);
            jobMetrics.itemFailed(JOB, "renewal");
        }
    }
}
