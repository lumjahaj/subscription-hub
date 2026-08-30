package dev.lumjahaj.subscription.hub.auth.api;

/**
 * The authorization rules, named once instead of being retyped as SpEL
 * string literals across a dozen controllers — where a typo like
 * {@code hasRole('BILING')} would not fail to compile and would simply
 * deny everyone, or worse, a missing one would allow everyone.
 *
 * <p><b>The convention these encode:</b> reads are available to any
 * authenticated role and therefore carry no annotation — the chain's
 * {@code anyRequest().authenticated()} already covers them, and adding
 * {@code isAuthenticated()} to every getter would be noise that obscures
 * the endpoints where the rule is actually interesting. Writes always
 * carry an explicit rule. So on a controller, an unannotated write method
 * is a bug, and an unannotated read is deliberate.
 *
 * <p>Roles come from the {@code roles} claim of the JWT, mapped to
 * {@code ROLE_}-prefixed authorities in SecurityConfig, which is what lets
 * {@code hasRole} work here.
 */
public final class Authorize {

    private Authorize() {
    }

    /**
     * Defining what the tenant sells — products, plans, entitlements, and
     * the prices billing will later charge from. Narrower than
     * {@link #COMMERCIAL} on purpose: someone who can run billing should
     * not be able to quietly change what the prices are.
     */
    public static final String CATALOG_WRITE = "hasRole('ADMIN')";

    /**
     * Acting on customers, subscriptions, usage and invoices — the
     * day-to-day operation of the billing system, as opposed to defining
     * its catalog.
     */
    public static final String COMMERCIAL = "hasAnyRole('ADMIN', 'BILLING')";
}
