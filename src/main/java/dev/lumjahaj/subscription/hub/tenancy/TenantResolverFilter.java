package dev.lumjahaj.subscription.hub.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantResolverFilter extends OncePerRequestFilter {

    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String MDC_TENANT = "tenantId";
    public static final String MDC_REQUEST = "requestId";

    private final TenantRepository tenants;

    public TenantResolverFilter(TenantRepository tenants) {
        this.tenants = tenants;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String requestId = UUID.randomUUID().toString();
        MDC.put(MDC_REQUEST, requestId);

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
        } finally {
            TenantContext.clear();
            MDC.remove(MDC_TENANT);
            MDC.remove(MDC_REQUEST);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Allow actuator health and swagger without tenant
        String path = request.getRequestURI();
        return path.startsWith("/actuator/health")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs");
    }
}
