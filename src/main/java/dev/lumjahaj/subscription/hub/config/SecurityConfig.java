package dev.lumjahaj.subscription.hub.config;

import dev.lumjahaj.subscription.hub.auth.domain.PlatformRole;
import dev.lumjahaj.subscription.hub.common.api.SecurityProblemHandler;
import dev.lumjahaj.subscription.hub.tenancy.api.TenantResolverFilter;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration
// Enables @PreAuthorize. The rules live next to the methods they guard
// (see Authorize) rather than as a wall of URL patterns here: a path
// pattern and the handler it protects drift apart silently, an annotation
// on the method cannot.
@EnableMethodSecurity
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
                        .requestMatchers(HttpMethod.POST, "/api/auth/token", "/api/platform/auth/token").permitAll()
                        // Liveness probes run before anything can authenticate.
                        .requestMatchers("/api/health", "/actuator/health").permitAll()
                        // Payment providers hold no token of ours. These
                        // requests authenticate by signature instead, which
                        // StripeWebhook verifies before anything is acted
                        // on; an unverified body is refused there. Keep
                        // TenantResolverFilter.shouldNotFilter in step.
                        .requestMatchers(HttpMethod.POST, "/api/webhooks/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        // Cross-tenant operations. A tenant ADMIN is refused
                        // here: administering one tenant confers nothing over
                        // the platform.
                        .requestMatchers("/api/platform/**").hasRole(PlatformRole.PLATFORM_ADMIN)
                        // Everything else under /actuator is platform
                        // infrastructure: authenticated at minimum, never
                        // blanket-public. Only health and info are exposed at
                        // all (see application.yml), so this is defence in
                        // depth against a future exposure change. Deliberately
                        // either kind of token for now; Observability decides
                        // who may read metrics.
                        .requestMatchers("/actuator/**").authenticated()
                        // Every tenant endpoint needs a token that names a
                        // tenant. A platform token is authenticated but has
                        // no tenant_id, so without this it would reach
                        // TenantResolverFilter and be answered 400
                        // TENANT_MISSING - true, but it describes a malformed
                        // request rather than a caller who isn't allowed here.
                        .anyRequest().access(hasTenantClaim()))
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
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
     * Grants tenant endpoints only to a token carrying a tenant_id claim.
     *
     * Checked on the claim rather than "does not have PLATFORM_ADMIN": an
     * allow-list of what a tenant request needs, not a deny-list of the
     * principals known today, so a future third kind of token is refused
     * until someone decides otherwise.
     *
     * An anonymous caller is denied too, and ExceptionTranslationFilter
     * turns a denial for an anonymous caller into 401 rather than 403 —
     * so this also still covers what authenticated() used to.
     */
    private static AuthorizationManager<RequestAuthorizationContext> hasTenantClaim() {
        return (authentication, context) -> new AuthorizationDecision(
                authentication.get() instanceof JwtAuthenticationToken jwt
                        && jwt.getToken().hasClaim(TenantResolverFilter.TENANT_CLAIM));
    }

    /**
     * Teaches the resource server where this application's roles live.
     *
     * Spring's default converter reads the OAuth2 `scope`/`scp` claims and
     * produces `SCOPE_`-prefixed authorities. AuthService issues a `roles`
     * claim instead, because these are application roles rather than
     * delegated OAuth scopes — so without this, every token would arrive
     * with no authorities at all and every @PreAuthorize would deny,
     * silently and uniformly.
     *
     * The `ROLE_` prefix is what lets hasRole('ADMIN') match: hasRole
     * prepends it, hasAuthority does not.
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
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
