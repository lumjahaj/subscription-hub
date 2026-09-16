package dev.lumjahaj.subscription.hub.auth.app;

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

    PasswordVerifier(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Returns the account only if it exists, is enabled and the password
     * matches; otherwise throws InvalidCredentialsException — one exception
     * for every reason, after one bcrypt verification in every case.
     */
    <T> T verify(Optional<T> found, Function<T, String> passwordHash, Predicate<T> enabled, String rawPassword) {
        // Always hash, even on the paths that already cannot succeed, so
        // response time doesn't reveal which accounts exist.
        String hashToCheck = found.map(passwordHash).orElse(DUMMY_HASH);
        boolean passwordMatches = passwordEncoder.matches(rawPassword, hashToCheck);

        return found
                .filter(account -> passwordMatches && enabled.test(account))
                .orElseThrow(InvalidCredentialsException::new);
    }
}
