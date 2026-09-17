package dev.lumjahaj.subscription.hub.common.metrics;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs an action once the current transaction has committed, or immediately
 * when there is none.
 *
 * For business counters. A counter incremented inside a transaction that
 * then rolls back counts something that never happened: an invoice-issued
 * count that includes invoices rolled back by a failing listener would
 * disagree with the database, and a dashboard that disagrees with the
 * database is worse than none. The opposite choice from the audit log, on
 * purpose: an audit row must commit *with* its change, while a metric only
 * needs to describe what did.
 *
 * A crash between commit and the increment loses one count. Metrics are
 * already lossy (a restart resets every counter), so that is acceptable here
 * and would not be for anything that has to add up exactly.
 */
public final class AfterCommit {

    private AfterCommit() {
    }

    public static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
