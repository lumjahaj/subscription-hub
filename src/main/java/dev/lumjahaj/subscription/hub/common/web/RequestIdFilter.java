package dev.lumjahaj.subscription.hub.common.web;

import dev.lumjahaj.subscription.hub.common.logging.MdcKeys;
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

/**
 * Stamps every request with a correlation id, in MDC, before anything else
 * runs.
 *
 * Split out of TenantResolverFilter when that filter moved inside the
 * security chain to read the tenant from a JWT. Tenant resolution now
 * happens *after* authentication — but a request rejected by Spring
 * Security never reaches it, and those responses still need a requestId
 * to correlate with the logs. Keeping the id here, at HIGHEST_PRECEDENCE
 * and outside the security chain, means every response carries one:
 * successes, business errors, 401s and 403s alike.
 *
 * It is also the honest separation. Correlating a request has nothing to
 * do with tenancy, and the two only ever shared a filter by accident of
 * history.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        MDC.put(MdcKeys.REQUEST_ID, UUID.randomUUID().toString());
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Pooled threads outlive requests; a stale id would silently
            // attach itself to the next unrelated request's logs.
            MDC.remove(MdcKeys.REQUEST_ID);
        }
    }
}
