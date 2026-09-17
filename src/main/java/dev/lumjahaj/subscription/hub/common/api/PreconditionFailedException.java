package dev.lumjahaj.subscription.hub.common.api;

import org.springframework.http.HttpStatus;

/**
 * The resource changed after the caller read it: If-Match names a version
 * that is no longer current (412). The caller should read it again, reapply
 * its change, and retry.
 *
 * Distinct from the 409 CONCURRENT_MODIFICATION a lost race at write time
 * produces: this one is detected before anything is written, from the
 * caller's own stale ETag.
 */
public class PreconditionFailedException extends BusinessRuleViolationException {

    public PreconditionFailedException(String resourceType, String identifier) {
        super(resourceType + " " + identifier + " has changed since it was read",
                "PRECONDITION_FAILED", HttpStatus.PRECONDITION_FAILED);
    }

    public PreconditionFailedException() {
        super("If-Match does not match the current version",
                "PRECONDITION_FAILED", HttpStatus.PRECONDITION_FAILED);
    }
}
