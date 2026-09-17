package dev.lumjahaj.subscription.hub.common.api;

import org.springframework.http.HttpStatus;

/**
 * An update that did not say which version it was based on (RFC 6585, 428).
 *
 * Refused rather than applied unconditionally: an update without If-Match is
 * exactly the blind overwrite optimistic locking exists to stop, and letting
 * it through would make the protection opt-in for the careless client, which
 * is the one that needs it.
 */
public class PreconditionRequiredException extends BusinessRuleViolationException {

    public PreconditionRequiredException() {
        super("This update requires an If-Match header carrying the ETag from a previous read",
                "PRECONDITION_REQUIRED", HttpStatus.PRECONDITION_REQUIRED);
    }
}
