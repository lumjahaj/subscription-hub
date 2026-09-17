package dev.lumjahaj.subscription.hub.common.api;

/**
 * ETags built from a JPA @Version, and the If-Match check that goes with them.
 *
 * The version is the whole ETag: it changes on every write, including writes
 * through other endpoints (setting a payment method bumps a customer's
 * version too), so it identifies the representation a client last saw.
 *
 * Strong, never weak (W/"3"): If-Match uses strong comparison (RFC 9110
 * 13.1.1), so a weak ETag could never satisfy it.
 */
public final class ETags {

    private ETags() {
    }

    public static String of(long version) {
        return "\"" + version + "\"";
    }

    /**
     * The version an update is based on, from its If-Match header.
     *
     * Missing is 428. "*" is treated as missing too: RFC 9110 lets it mean
     * "any current version", which is an unconditional overwrite by another
     * name. Anything else that is not exactly one of our ETags - a weak tag,
     * a list, garbage - can never match, so it is 412.
     */
    public static long requireIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank() || ifMatch.trim().equals("*")) {
            throw new PreconditionRequiredException();
        }
        String value = ifMatch.trim();
        if (value.length() < 3 || !value.startsWith("\"") || !value.endsWith("\"")) {
            throw new PreconditionFailedException();
        }
        try {
            return Long.parseLong(value.substring(1, value.length() - 1));
        } catch (NumberFormatException ex) {
            throw new PreconditionFailedException();
        }
    }
}
