package dev.lumjahaj.subscription.hub.auth.app;

import dev.lumjahaj.subscription.hub.auth.domain.PlatformUserRepository;
import dev.lumjahaj.subscription.hub.auth.infra.jpa.PlatformUserEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the first platform administrator from configuration.
 *
 * <p>Every system with an admin API has this chicken-and-egg: creating an
 * administrator needs an administrator. The dev profile solves it with a
 * seed (V9002), but seeds are never on the Flyway path outside dev — by
 * design, since their password is public — so a real deployment needs
 * another route that doesn't involve hand-written SQL against production.
 *
 * <p><b>Only while the table is empty.</b> The variables are a way in, not
 * a standing credential: once any platform user exists this does nothing,
 * so leaving them set (or rotating them) can't create a second admin or
 * reset the first one's password behind anybody's back.
 *
 * <p>Both unset is the normal case and a no-op. Exactly one set is a
 * misconfiguration and fails startup, the same "fail at boot, not at first
 * use" choice JwtConfig makes for a short signing secret.
 */
@Component
public class PlatformAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdminBootstrap.class);

    /** Matches the minimum a human would be asked for; this account can create tenants. */
    static final int MIN_PASSWORD_LENGTH = 12;

    private final PlatformUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;

    public PlatformAdminBootstrap(
            PlatformUserRepository users,
            PasswordEncoder passwordEncoder,
            @Value("${platform.bootstrap.admin-email:}") String email,
            @Value("${platform.bootstrap.admin-password:}") String password
    ) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean emailSet = email != null && !email.isBlank();
        boolean passwordSet = password != null && !password.isBlank();

        if (!emailSet && !passwordSet) {
            return;
        }
        if (emailSet != passwordSet) {
            throw new IllegalStateException(
                    "platform.bootstrap.admin-email and platform.bootstrap.admin-password must be set together");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "platform.bootstrap.admin-password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (users.anyExist()) {
            log.info("Platform administrator already exists; bootstrap credentials ignored");
            return;
        }

        PlatformUserEntity admin = new PlatformUserEntity();
        admin.setEmail(email);
        admin.setPasswordHash(passwordEncoder.encode(password));
        try {
            users.save(admin);
            // The email is logged, the password never is.
            log.info("Created bootstrap platform administrator {}", email);
        } catch (DataIntegrityViolationException ex) {
            // Two instances starting together can both see an empty table;
            // uk_platform_user_email lets exactly one insert win. The loser
            // has nothing left to do, so it must not fail startup over it.
            log.info("Bootstrap platform administrator {} was created concurrently by another instance", email);
        }
    }
}
