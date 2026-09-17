package dev.lumjahaj.subscription.hub.audit.api;

import dev.lumjahaj.subscription.hub.common.api.BusinessRuleViolationException;
import org.springframework.http.HttpStatus;

/**
 * entityType without entityId, or the reverse. Refused rather than silently
 * ignored: returning the unfiltered timeline to a caller who asked for one
 * record's history would look like that record's history.
 */
public class IncompleteAuditFilterException extends BusinessRuleViolationException {

    public IncompleteAuditFilterException() {
        super("entityType and entityId must be given together", "AUDIT_FILTER_INCOMPLETE", HttpStatus.BAD_REQUEST);
    }
}
