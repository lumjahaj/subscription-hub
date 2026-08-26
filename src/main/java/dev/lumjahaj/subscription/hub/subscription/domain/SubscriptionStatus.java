package dev.lumjahaj.subscription.hub.subscription.domain;

public enum SubscriptionStatus {
    TRIALING,
    ACTIVE,
    PAST_DUE,
    CANCELED,
    PAUSED   // added via V2 migration, not in the original V1 enum
}
