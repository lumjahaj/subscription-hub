package dev.lumjahaj.subscription.hub.config;

import dev.lumjahaj.subscription.hub.common.api.SecurityProblemHandler;
import dev.lumjahaj.subscription.hub.tenancy.api.TenantResolverFilter;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration
public class SecurityConfig {

    private final TenantRepository tenants;
    private final HandlerExceptionResolver handlerExceptionResolver;
    private final SecurityProblemHandler problemHandler;

    public SecurityConfig(
            TenantRepository tenants,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver,
            SecurityProblemHandler problemHandler
    ) {
        this.tenants = tenants;
        this.handlerExceptionResolver = handlerExceptionResolver;
        this.problemHandler = problemHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Stateless bearer tokens, never cookies, so there is no
                // ambient credential a cross-site form post could ride on -
                // which is the only thing CSRF protection defends against.
                // (The previous "disable for simplicity" was true but not a
                // reason.)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Obtaining a token cannot itself require one.
                        .requestMatchers(HttpMethod.POST, "/api/auth/token").permitAll()
                        // Liveness probes run before anything can authenticate.
                        .requestMatchers("/api/health", "/actuator/health").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        // Everything else under /actuator is platform
                        // infrastructure: authenticated at minimum, never
                        // blanket-public. Only health and info are exposed at
                        // all (see application.yml), so this is defence in
                        // depth against a future exposure change.
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> {})
                        .authenticationEntryPoint(problemHandler))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                // After AuthorizationFilter, not merely after
                // BearerTokenAuthenticationFilter. The latter only
                // *authenticates* when a token is present; enforcing
                // authenticated() happens later, in AuthorizationFilter.
                // Sitting between the two meant an unauthenticated request
                // reached the tenant filter with an empty SecurityContext
                // and was rejected as 400 TENANT_MISSING before Security
                // could answer 401. Placing it last means it only ever runs
                // for requests that are already authenticated and allowed.
                .addFilterAfter(tenantResolverFilter(), AuthorizationFilter.class);

        return http.build();
    }

    /**
     * Constructed directly rather than declared as a @Bean, and this is
     * load-bearing: Spring Boot auto-registers every Filter bean into the
     * servlet chain, so a bean added here would run twice — once early with
     * no principal (throwing "no tenant"), and once in the correct
     * position. The alternative is a FilterRegistrationBean with
     * setEnabled(false) to suppress the automatic registration, which is
     * more machinery to achieve the same thing.
     */
    private TenantResolverFilter tenantResolverFilter() {
        return new TenantResolverFilter(tenants, handlerExceptionResolver);
    }
}
