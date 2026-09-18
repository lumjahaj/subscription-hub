package dev.lumjahaj.subscription.hub.payment.app;

import dev.lumjahaj.subscription.hub.common.logging.MdcKeys;
import dev.lumjahaj.subscription.hub.common.metrics.JobMetrics;
import dev.lumjahaj.subscription.hub.payment.infra.jpa.PaymentEntity;
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
 * Finds payments no provider event ever settled and hands each to
 * PaymentReconciliationService. Thin trigger only, like BillingCycleJob,
 * DunningJob and NotificationRelayJob.
 *
 * Iterates active tenants only, per CLAUDE.md §4's deactivation policy: on a
 * real provider, reconciling a payment with no reference re-issues the create,
 * which can charge a card, and a deactivated tenant is one the platform has
 * stopped acting for. Its stuck payments are resolved on reactivation instead,
 * the same way missed billing periods are.
 */
@Component
public class PaymentReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationJob.class);
    private static final String JOB = "payment-reconciliation";

    private final TenantRepository tenants;
    private final PaymentReconciliationService reconciliation;
    private final JobMetrics jobMetrics;

    public PaymentReconciliationJob(
            TenantRepository tenants,
            PaymentReconciliationService reconciliation,
            JobMetrics jobMetrics
    ) {
        this.tenants = tenants;
        this.reconciliation = reconciliation;
        this.jobMetrics = jobMetrics;
    }

    @Scheduled(cron = "${payment.reconciliation.cron}")
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
            for (PaymentEntity payment : reconciliation.findDue(tenantId, now)) {
                reconcileOneSafely(payment);
            }
        } finally {
            MDC.remove(MdcKeys.TENANT_ID);
        }
    }

    /**
     * One unreachable provider, or one payment that will not settle, must not
     * end the tenant's run. The payment stays PENDING and the next run asks
     * again; the gauge is what escalates it if it never resolves.
     */
    private void reconcileOneSafely(PaymentEntity payment) {
        UUID paymentId = payment.getId();
        try {
            reconciliation.reconcile(payment);
        } catch (Exception ex) {
            log.error("Could not reconcile payment {}; it stays PENDING for the next run", paymentId, ex);
            jobMetrics.itemFailed(JOB, "reconcile");
        }
    }
}
