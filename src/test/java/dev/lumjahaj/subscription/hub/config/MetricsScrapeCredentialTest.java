package dev.lumjahaj.subscription.hub.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The unconfigured and misconfigured branches, which no integration test can
 * reach: every test context sets a valid scrape account.
 */
class MetricsScrapeCredentialTest {

    @Test
    void bothUnset_isUnconfiguredRatherThanAnError() {
        assertThat(MetricsScrapeCredential.from("", "").configured()).isFalse();
        assertThat(MetricsScrapeCredential.from(null, null).configured()).isFalse();
    }

    @Test
    void bothSet_isConfigured() {
        assertThat(MetricsScrapeCredential.from("prometheus", "a-long-enough-password").configured()).isTrue();
    }

    @Test
    void oneWithoutTheOther_failsStartup() {
        assertThatThrownBy(() -> MetricsScrapeCredential.from("prometheus", ""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> MetricsScrapeCredential.from("", "a-long-enough-password"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aShortPassword_failsStartup() {
        assertThatThrownBy(() -> MetricsScrapeCredential.from("prometheus", "short"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least");
    }

    @Test
    void toString_neverContainsThePassword() {
        assertThat(MetricsScrapeCredential.from("prometheus", "a-long-enough-password").toString())
                .doesNotContain("a-long-enough-password");
    }
}
