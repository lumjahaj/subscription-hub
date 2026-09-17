package dev.lumjahaj.subscription.hub.config;

/**
 * The single account Prometheus uses to read /actuator/prometheus.
 *
 * Its own credential rather than a JWT: Prometheus holds a static secret in
 * its config, and this application's tokens expire after an hour. And not a
 * tenant or platform account either - metrics are read by infrastructure,
 * and a leaked scrape secret should unlock the metrics and nothing else.
 *
 * Both unset means metrics are simply not readable (every scrape is 401),
 * which keeps a clean clone free of secrets. One without the other, or a
 * short password, fails startup - the same rule PlatformAdminBootstrap
 * applies - because a half-configured credential is a mistake, not a choice.
 */
record MetricsScrapeCredential(String username, String password) {

    static final int MIN_PASSWORD_LENGTH = 16;

    static MetricsScrapeCredential from(String username, String password) {
        boolean hasUsername = username != null && !username.isBlank();
        boolean hasPassword = password != null && !password.isBlank();
        if (hasUsername != hasPassword) {
            throw new IllegalStateException(
                    "metrics.scrape.username and metrics.scrape.password must be set together");
        }
        if (hasPassword && password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "metrics.scrape.password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        return new MetricsScrapeCredential(hasUsername ? username : null, hasPassword ? password : null);
    }

    boolean configured() {
        return username != null;
    }

    /** Never the password, should this ever end up in a log line. */
    @Override
    public String toString() {
        return "MetricsScrapeCredential[username=" + username + "]";
    }
}
