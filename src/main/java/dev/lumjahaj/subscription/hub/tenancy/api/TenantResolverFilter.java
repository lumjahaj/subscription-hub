package dev.lumjahaj.subscription.hub.tenancy.api;

import dev.lumjahaj.subscription.hub.common.logging.MdcKeys;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantResolverFilter extends OncePerRequestFilter {

    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String MDC_TENANT = MdcKeys.TENANT_ID;
    public static final String MDC_REQUEST = MdcKeys.REQUEST_ID;

    private final TenantRepository tenants;
    private final HandlerExceptionResolver resolver;

    public TenantResolverFilter(
            TenantRepository tenants,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver
    ) {
        this.tenants = tenants;
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        MDC.put(MDC_REQUEST, UUID.randomUUID().toString());

        try {
            String tenantId = request.getHeader(TENANT_HEADER);
            if (tenantId == null || tenantId.isBlank()) {
                throw new MissingTenantException();
            }

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
            MDC.remove(MDC_REQUEST);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                // Login cannot require a resolved tenant: establishing which
                // tenant the caller belongs to is precisely what it does, and
                // it takes the tenant in its body rather than a header. Without
                // this the filter rejects every login with TENANT_MISSING
                // before the controller is ever reached.
                || path.startsWith("/api/auth");
    }
}
