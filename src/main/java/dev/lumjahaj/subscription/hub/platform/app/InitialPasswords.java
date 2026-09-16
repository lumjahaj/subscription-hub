package dev.lumjahaj.subscription.hub.platform.app;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates a new tenant administrator's first password.
 *
 * <p>Generated rather than chosen by the platform operator, so nobody ever
 * picks a password on the tenant's behalf and nothing sends one in a
 * request body. It is returned once, in the provisioning response, and only
 * its bcrypt hash is stored — the same show-once contract as an API key.
 *
 * <p>24 bytes from SecureRandom is 192 bits, far beyond guessing, and
 * base64url keeps it copy-pasteable (no {@code +}, {@code /} or padding).
 */
final class InitialPasswords {

    private static final int BYTES = 24;
    private static final SecureRandom RANDOM = new SecureRandom();

    private InitialPasswords() {
    }

    static String generate() {
        byte[] bytes = new byte[BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
