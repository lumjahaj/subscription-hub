package dev.lumjahaj.subscription.hub.auth.app;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The login check both AuthService and PlatformAuthService run, extracted
 * so the enumeration defence exists once rather than as two copies that
 * could drift — a second login that forgot to hash on the not-found path
 * would quietly reopen the timing leak the first one closes.
 */
@Component
class PasswordVerifier {

    /**
     * A valid bcrypt hash of a value nothing will ever submit. Used to
     * spend the same ~100ms of hashing when no user was found as when one
     * was, so response time doesn't reveal which emails exist — the same
     * reason every failure throws the identical exception.
     */
    private static final String DUMMY_HASH =
            "$2a$10$weWhaGYAMz9Fzc9M.xikWefRTaxBFywEYnMyYYZYz/LTSnaeP8gci";

    private final PasswordEncoder passwordEncoder;
    private final MeterRegistry registry;

    PasswordVerifier(PasswordEncoder passwordEncoder, MeterRegistry registry) {
        this.passwordEncoder = passwordEncoder;
        this.registry = registry;
    }

    /**
     * Returns the account only if it exists, is enabled and the password
     * matches; otherwise throws InvalidCredentialsException — one exception
     * for every reason, after one bcrypt verification in every case.
     */
    <T> T verify(
            String principal, Optional<T> found, Function<T, String> passwordHash, Predicate<T> enabled, String rawPassword) {
        // Always hash, even on the paths that already cannot succeed, so
        // response time doesn't reveal which accounts exist.
        String hashToCheck = found.map(passwordHash).orElse(DUMMY_HASH);
        boolean passwordMatches = passwordEncoder.matches(rawPassword, hashToCheck);

        Optional<T> verified = found.filter(account -> passwordMatches && enabled.test(account));
        if (verified.isEmpty()) {
            countFailure(principal);
            throw new InvalidCredentialsException();
        }
        return verified.get();
    }

    /**
     * auth.login.failures (principal: tenant or platform). The metric the audit
     * log deliberately leaves out: a failed login for an unknown tenant cannot
     * even be an audit row, and what matters about failures is their rate, for
     * an alert on a brute-force spike. No reason tag - one reason for every
     * failure is the enumeration defence, and a metrics endpoint must not undo
     * it - and no tenant or email, which would be unbounded and would name
     * accounts under attack.
     */
    private void countFailure(String principal) {
        Counter.builder("auth.login.failures")
                .description("Rejected login attempts")
                .tag("principal", principal)
                .register(registry)
                .increment();
    }
}
