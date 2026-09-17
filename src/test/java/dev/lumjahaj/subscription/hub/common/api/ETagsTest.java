package dev.lumjahaj.subscription.hub.common.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ETagsTest {

    @Test
    void roundTripsAVersion() {
        assertThat(ETags.of(7)).isEqualTo("\"7\"");
        assertThat(ETags.requireIfMatch(ETags.of(7))).isEqualTo(7);
        assertThat(ETags.requireIfMatch("  \"7\" ")).isEqualTo(7);
    }

    @Test
    void missingOrWildcardIsPreconditionRequired() {
        assertThatThrownBy(() -> ETags.requireIfMatch(null)).isInstanceOf(PreconditionRequiredException.class);
        assertThatThrownBy(() -> ETags.requireIfMatch(" ")).isInstanceOf(PreconditionRequiredException.class);
        assertThatThrownBy(() -> ETags.requireIfMatch("*")).isInstanceOf(PreconditionRequiredException.class);
    }

    @Test
    void anythingThatCannotBeOneOfOurETagsIsPreconditionFailed() {
        // Weak tags never satisfy If-Match's strong comparison; unquoted,
        // non-numeric and list values can never equal a single version.
        for (String value : new String[] {"W/\"7\"", "7", "\"\"", "\"abc\"", "\"1\", \"2\""}) {
            assertThatThrownBy(() -> ETags.requireIfMatch(value))
                    .as(value)
                    .isInstanceOf(PreconditionFailedException.class);
        }
    }
}
