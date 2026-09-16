package dev.lumjahaj.subscription.hub.auth.app;

import dev.lumjahaj.subscription.hub.auth.domain.PlatformUserRepository;
import dev.lumjahaj.subscription.hub.auth.infra.jpa.PlatformUserEntity;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A plain unit test rather than an integration test: under the dev profile
 * every test database already holds the V9002 seed, so the one branch that
 * matters — "table is empty" — can't be reached there. A hand-written
 * in-memory repository keeps to the codebase's no-Mockito style.
 */
class PlatformAdminBootstrapTest {

    private static final String PASSWORD = "a-long-enough-password";

    private final InMemoryPlatformUsers users = new InMemoryPlatformUsers();
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);

    @Test
    void createsTheAdministrator_whenNoneExists() {
        bootstrap("ops@example.com", PASSWORD).run(null);

        assertThat(users.saved).hasSize(1);
        PlatformUserEntity admin = users.saved.get(0);
        assertThat(admin.getEmail()).isEqualTo("ops@example.com");
        assertThat(admin.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(encoder.matches(PASSWORD, admin.getPasswordHash())).isTrue();
    }

    @Test
    void doesNothing_whenAnAdministratorAlreadyExists() {
        users.saved.add(new PlatformUserEntity());

        bootstrap("someone-else@example.com", PASSWORD).run(null);

        assertThat(users.saved).hasSize(1);
    }

    @Test
    void doesNothing_whenNotConfigured() {
        bootstrap("", "").run(null);

        assertThat(users.saved).isEmpty();
    }

    @Test
    void failsStartup_whenOnlyOneValueIsSet() {
        assertThatThrownBy(() -> bootstrap("ops@example.com", "").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be set together");
    }

    @Test
    void failsStartup_whenThePasswordIsTooShort() {
        assertThatThrownBy(() -> bootstrap("ops@example.com", "short").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least");
        assertThat(users.saved).isEmpty();
    }

    private PlatformAdminBootstrap bootstrap(String email, String password) {
        return new PlatformAdminBootstrap(users, encoder, email, password);
    }

    private static final class InMemoryPlatformUsers implements PlatformUserRepository {

        private final List<PlatformUserEntity> saved = new ArrayList<>();

        @Override
        public Optional<PlatformUserEntity> findByEmail(String email) {
            return saved.stream().filter(u -> email.equals(u.getEmail())).findFirst();
        }

        @Override
        public boolean anyExist() {
            return !saved.isEmpty();
        }

        @Override
        public PlatformUserEntity save(PlatformUserEntity user) {
            saved.add(user);
            return user;
        }
    }
}
