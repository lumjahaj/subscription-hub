package dev.lumjahaj.subscription.hub.subscription.app;

import dev.lumjahaj.subscription.hub.common.api.BusinessRuleViolationException;
import dev.lumjahaj.subscription.hub.subscription.domain.SubscriptionStatus;
import org.springframework.http.HttpStatus;

/**
 * The subscription exists and the request is well-formed, but the
 * requested transition (cancel/pause/resume) doesn't make sense from its
 * current status.
 */
public class InvalidSubscriptionStateException extends BusinessRuleViolationException {

    public InvalidSubscriptionStateException(String action, SubscriptionStatus currentStatus) {
        super("Cannot " + action + " subscription in status " + currentStatus,
                "INVALID_SUBSCRIPTION_STATE", HttpStatus.CONFLICT);
    }
}
