package dev.lumjahaj.subscription.hub.notification.domain;

import java.util.UUID;

/**
 * The SQS message body: just enough to look the row back up. Content is
 * never carried on the queue - it already lives on the notification row,
 * rendered at enqueue time - so the message stays small and nothing about
 * the customer or the invoice passes through a third party's queue.
 *
 * The tenant travels explicitly because the listener has no request and no
 * caller-supplied context to read it from; {@code SqsNotificationListener}
 * uses it to open {@code TenantContext.runAs} before touching the database,
 * the same requirement the Stripe webhook has for the opposite reason (here
 * there is no open-in-view session to fight - the transaction opens after
 * the tenant is set).
 */
public record NotificationMessage(String tenantId, UUID notificationId) {
}
