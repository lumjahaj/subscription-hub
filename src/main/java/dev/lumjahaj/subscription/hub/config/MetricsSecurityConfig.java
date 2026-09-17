package dev.lumjahaj.subscription.hub.config;

import dev.lumjahaj.subscription.hub.common.api.SecurityProblemHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * A separate filter chain for /actuator/prometheus, ordered ahead of
 * SecurityConfig's so it is the only chain that ever sees that path.
 *
 * Separate rather than one more rule in the main chain because the two
 * authenticate differently: the main chain accepts bearer JWTs and nothing
 * else, this one accepts HTTP Basic for the scrape account and nothing else.
 * In one chain, enabling Basic would let it be tried on every tenant
 * endpoint too. Here a JWT, even a platform administrator's, is ignored and
 * answered 401: holding the platform is not the same as being the scraper.
 *
 * Its AuthenticationManager is local to the chain, not a UserDetailsService
 * bean, so Boot's security auto-configuration never picks the scrape
 * account up and offers it anywhere else.
 */
@Configuration
public class MetricsSecurityConfig {

    static final String SCRAPE_ROLE = "METRICS_SCRAPER";
    static final String PROMETHEUS_PATH = "/actuator/prometheus";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain metricsScrapeFilterChain(
            HttpSecurity http,
            SecurityProblemHandler problemHandler,
            PasswordEncoder passwordEncoder,
            @Value("${metrics.scrape.username:}") String username,
            @Value("${metrics.scrape.password:}") String password
    ) throws Exception {
        MetricsScrapeCredential credential = MetricsScrapeCredential.from(username, password);

        http
                .securityMatcher(PROMETHEUS_PATH)
                // Stateless and credentialed on every request, like the main
                // chain, so there is no session for CSRF to protect.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler))
                .formLogin(form -> form.disable())
                .authorizeHttpRequests(auth -> {
                    if (credential.configured()) {
                        auth.anyRequest().hasRole(SCRAPE_ROLE);
                    } else {
                        // Unconfigured means closed, never open.
                        auth.anyRequest().denyAll();
                    }
                });

        // Basic is only switched on when there is an account to check it
        // against; unconfigured, the chain has no way to authenticate at all.
        if (credential.configured()) {
            // Hashed once here and verified with the application's bcrypt
            // encoder on each scrape: a few tens of milliseconds per scrape
            // interval, and the standard DaoAuthenticationProvider path
            // rather than a hand-written String comparison, whose early
            // exit on the first differing character leaks timing.
            InMemoryUserDetailsManager users = new InMemoryUserDetailsManager(User.withUsername(credential.username())
                    .password(passwordEncoder.encode(credential.password()))
                    .roles(SCRAPE_ROLE)
                    .build());
            DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
            provider.setPasswordEncoder(passwordEncoder);
            http.authenticationManager(new ProviderManager(provider))
                    .httpBasic(basic -> basic.authenticationEntryPoint(problemHandler));
        }

        return http.build();
    }
}
