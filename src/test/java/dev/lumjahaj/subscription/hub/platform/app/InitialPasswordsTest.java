package dev.lumjahaj.subscription.hub.platform.app;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InitialPasswordsTest {

    @Test
    void isUrlSafeAndCarries192BitsOfRandomness() {
        String password = InitialPasswords.generate();

        // 24 bytes base64url-encoded without padding is 32 characters.
        assertThat(password).hasSize(32).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void neverRepeats() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            seen.add(InitialPasswords.generate());
        }
        assertThat(seen).hasSize(1_000);
    }
}
