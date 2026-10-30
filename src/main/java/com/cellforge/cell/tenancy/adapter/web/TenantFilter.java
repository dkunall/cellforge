package com.cellforge.cell.tenancy.adapter.web;

import com.cellforge.cell.access.application.Members;
import com.cellforge.cell.access.domain.Member;
import com.cellforge.cell.tenancy.application.EntitlementSource;
import com.cellforge.cell.tenancy.application.HostedTenants;
import com.cellforge.cell.tenancy.application.TenantBulkhead;
import com.cellforge.cell.tenancy.application.TenantContext;
import com.cellforge.cell.tenancy.application.TenantContext.Mode;
import com.cellforge.cell.tenancy.application.TenantContext.Scope;
import com.cellforge.cell.tenancy.domain.Entitlements;
import com.cellforge.cell.tenancy.domain.HostedTenant;
import com.cellforge.cell.tenancy.domain.TenantStatus;
import com.cellforge.shared.web.ProblemWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns "this token, this tenant" into a member scope for the rest of the request. The tenant id comes from
 * the header the edge sets after resolving the workspace slug, never from a request body. Runs after Spring
 * Security, so the token is already verified.
 */
@Component
class TenantFilter extends OncePerRequestFilter {

    static final String TENANT_HEADER = "X-Tenant-Id";
    /** The one call a non-member may make: accepting an invite, which is what makes them a member. */
    private static final String ACCEPT_PATH = "/v1/invites/accept";
    private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration BULKHEAD_WAIT = Duration.ofMillis(250);

    private record Resolution(Optional<HostedTenant> tenant, Optional<Member> member) {
    }

    private final HostedTenants tenants;
    private final Members members;
    private final EntitlementSource entitlements;
    private final TenantBulkhead bulkhead;
    private final TransactionTemplate readOnly;
    private final ObjectMapper json;

    TenantFilter(HostedTenants tenants, Members members, EntitlementSource entitlements, TenantBulkhead bulkhead,
                 PlatformTransactionManager transactions, ObjectMapper json) {
        this.tenants = tenants;
        this.members = members;
        this.entitlements = entitlements;
        this.bulkhead = bulkhead;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwt)) {
            chain.doFilter(request, response);
            return;
        }
        UUID tenantId = parse(request.getHeader(TENANT_HEADER));
        if (tenantId == null) {
            ProblemWriter.write(response, json, HttpStatus.BAD_REQUEST, "tenant-required", "Missing or invalid " + TENANT_HEADER);
            return;
        }
        Scope probe = new Scope(tenantId, Mode.MEMBER, null, LOOKUP_TIMEOUT);
        String userId = jwt.getToken().getSubject();
        Resolution found = TenantContext.call(probe, () -> readOnly.execute(s ->
            new Resolution(tenants.current(), members.find(userId))));
        if (found.tenant().isEmpty() || found.tenant().get().status() == TenantStatus.MOVED) {
            ProblemWriter.write(response, json, HttpStatusCode.valueOf(421), "tenant-not-hosted",
                "Tenant is not hosted on this cell");
            return;
        }
        boolean accepting = "POST".equals(request.getMethod()) && ACCEPT_PATH.equals(request.getRequestURI());
        if (found.member().isEmpty() && !accepting) {
            ProblemWriter.write(response, json, HttpStatus.FORBIDDEN, "not-a-member", "You are not a member of this workspace");
            return;
        }
        Entitlements plan = entitlements.forTenant(tenantId);
        TenantBulkhead.Permit permit;
        try {
            permit = bulkhead.tryEnter(tenantId, plan.maxConcurrentRequests(), BULKHEAD_WAIT).orElse(null);
            if (permit == null) {
                response.setHeader("Retry-After", "1");
                ProblemWriter.write(response, json, HttpStatus.TOO_MANY_REQUESTS, "tenant-busy",
                    "Too many requests in flight for this workspace");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServletException(e);
        }
        try (permit) {
            Scope scope = probe.withMember(found.member().orElse(null), plan.statementTimeout());
            TenantContext.call(scope, () -> {
                try {
                    chain.doFilter(request, response);
                    return null;
                } catch (IOException | ServletException e) {
                    throw new FilterFailure(e);
                }
            });
        } catch (FilterFailure e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw (ServletException) e.getCause();
        }
    }

    private static UUID parse(String header) {
        if (header == null) {
            return null;
        }
        try {
            return UUID.fromString(header);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static final class FilterFailure extends RuntimeException {
        FilterFailure(Exception cause) {
            super(cause);
        }
    }
}
