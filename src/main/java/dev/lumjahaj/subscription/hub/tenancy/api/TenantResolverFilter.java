package dev.lumjahaj.subscription.hub.tenancy.api;

import dev.lumjahaj.subscription.hub.common.logging.MdcKeys;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;

/**
 * Populates TenantContext from the authenticated caller's JWT.
 *
 * <p>The tenant used to come from an {@code X-Tenant-Id} header, which any
 * client could set to anything — so every tenant-scoped query in the
 * application was ultimately trusting a string the caller typed. It now
 * comes from the {@code tenant_id} claim of a signed token, which the
 * caller cannot forge without the signing secret. The header is gone.
 *
 * <p><b>This filter is deliberately not a {@code @Component} and carries no
 * {@code @Order}.</b> It has to run *after* Spring Security has
 * authenticated the request, because it reads the result of that
 * authentication — at its old {@code HIGHEST_PRECEDENCE} it ran before the
 * security chain and would always see an empty SecurityContext. It is
 * therefore constructed and registered inside the chain by SecurityConfig
 * via {@code addFilterAfter(BearerTokenAuthenticationFilter.class)}.
 *
 * <p>Being a bean would actively break it: Spring Boot auto-registers any
 * {@code Filter} bean into the servlet chain, so it would run twice — once
 * too early with no principal, and once in the right place.
 */
public class TenantResolverFilter extends OncePerRequestFilter {

    public static final String TENANT_CLAIM = "tenant_id";
    public static final String MDC_TENANT = MdcKeys.TENANT_ID;

    private final TenantRepository tenants;
    private final HandlerExceptionResolver resolver;

    public TenantResolverFilter(TenantRepository tenants, HandlerExceptionResolver resolver) {
        this.tenants = tenants;
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        try {
            String tenantId = tenantIdFromToken();

            // Re-checked on every request rather than trusted from the
            // token: a tenant deactivated after a token was issued must
            // stop working immediately, not when the token happens to
            // expire.
            var tenant = tenants.findActiveById(tenantId)
                    .orElseThrow(() -> new UnknownTenantException(tenantId));

            TenantContext.setTenantId(tenant.id());
            MDC.put(MDC_TENANT, tenant.id());

            filterChain.doFilter(request, response);

        } catch (Exception ex) {
            // Delegate to Spring so @ControllerAdvice can build a ProblemDetail response
            resolver.resolveException(request, response, null, ex);
        } finally {
            TenantContext.clear();
            MDC.remove(MDC_TENANT);
        }
    }

    /**
     * By the time this runs, the path is one that requires
     * authentication (see shouldNotFilter) and Spring Security has already
     * rejected anyone without a valid token. So no JWT here, or a JWT
     * carrying no tenant_id, means a token was minted by something that
     * isn't AuthService — anomalous rather than an ordinary client
     * mistake. MissingTenantException survived the move from
     * header-based resolution with exactly that narrower meaning.
     */
    private String tenantIdFromToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            throw new MissingTenantException();
        }
        String tenantId = jwtAuthentication.getToken().getClaimAsString(TENANT_CLAIM);
        if (tenantId == null || tenantId.isBlank()) {
            throw new MissingTenantException();
        }
        return tenantId;
    }

    /**
     * Skips the paths SecurityConfig permits without authentication.
     *
     * Being inside the security chain is not the same as running only on
     * authenticated requests: a permitAll path still passes through every
     * filter, just with an empty SecurityContext. Without this list the
     * filter demanded a tenant from requests that by definition cannot
     * have one yet — login most of all, where establishing the tenant is
     * the entire point — and rejected every one of them with
     * TENANT_MISSING before the controller was reached.
     *
     * Kept in step with SecurityConfig's permitAll matchers.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/auth")
                || path.startsWith("/api/health")
                // A provider webhook carries no token, so there is no claim
                // to read; its tenant comes from the signed event body and
                // is established by PaymentWebhookService instead.
                || path.startsWith("/api/webhooks")
                || path.startsWith("/actuator")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs");
    }
}
