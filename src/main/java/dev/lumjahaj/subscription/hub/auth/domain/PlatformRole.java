package dev.lumjahaj.subscription.hub.auth.domain;

/**
 * The role a platform token carries in its {@code roles} claim.
 *
 * <p>Deliberately not a value of {@link Role}. Role is the vocabulary of
 * <i>tenant</i> accounts, stored in app_user_role and constrained by
 * chk_app_user_role_value; putting PLATFORM_ADMIN there would make it
 * grantable to a tenant user by a single row insert. Kept apart, a tenant
 * account cannot hold it even in principle.
 */
public final class PlatformRole {

    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";

    private PlatformRole() {
    }
}
