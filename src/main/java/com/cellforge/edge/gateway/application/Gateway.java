package com.cellforge.edge.gateway.application;

import com.cellforge.edge.billing.application.Accounts;
import com.cellforge.edge.directory.application.Directory;
import com.cellforge.edge.directory.application.Routes;
import com.cellforge.edge.directory.domain.RouteStatus;
import com.cellforge.edge.directory.domain.TenantRoute;
import com.cellforge.shared.error.NotFoundException;
import com.cellforge.shared.error.PaymentRequiredException;
import com.cellforge.shared.error.RateLimitedException;
import com.cellforge.shared.error.UnavailableException;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The public request path: resolve the workspace, check its standing and limits, hand the request to its
 * cell, count it.
 */
@Service
public class Gateway {

    private static final int MISDIRECTED = 421;
    private static final Set<String> READS = Set.of("GET", "HEAD");

    private final Routes routes;
    private final Directory directory;
    private final Accounts accounts;
    private final Admission admission;
    private final CellForwarder forwarder;
    private final UsageEvents usage;
    private final Clock clock;

    public Gateway(Routes routes, Directory directory, Accounts accounts, Admission admission, CellForwarder forwarder,
                   UsageEvents usage, Clock clock) {
        this.routes = routes;
        this.directory = directory;
        this.accounts = accounts;
        this.admission = admission;
        this.forwarder = forwarder;
        this.usage = usage;
        this.clock = clock;
    }

    public CellForwarder.Inbound handle(String slug, CellForwarder.Outbound request) {
        TenantRoute route = routes.resolve(slug)
            .orElseThrow(() -> new NotFoundException("tenant-not-found", "No workspace called " + slug));
        if (route.status() == RouteStatus.PROVISIONING) {
            throw new UnavailableException("tenant-provisioning", "Workspace is still being created", 2);
        }
        Accounts.Standing standing = accounts.standing(route.id());
        if (!READS.contains(request.method()) && !standing.subscription().status().allowsWrites()) {
            throw new PaymentRequiredException("tenant-suspended",
                "Workspace is " + standing.subscription().status().name().toLowerCase() + "; update billing to make changes");
        }
        Admission.Decision decision = admission.admit(route.id(), standing.plan());
        switch (decision.outcome()) {
            case RATE_LIMITED -> throw new RateLimitedException("rate-limited", "Request rate limit reached",
                (int) Math.max(1, (decision.retryAfterMillis() + 999) / 1000));
            case QUOTA_EXHAUSTED -> throw new RateLimitedException("quota-exhausted",
                "Monthly API quota for this plan is used up", 3600);
            case ADMITTED -> { }
        }

        CellForwarder.Inbound response = forwarder.forward(route.cellUrl(), route.id(), request);
        if (response.status() == MISDIRECTED) {
            // The tenant moved since we cached its route. One retry against the fresh entry is enough: moves
            // are rare and a second 421 means something is genuinely wrong.
            routes.evict(slug);
            TenantRoute fresh = directory.bySlug(slug).orElse(route);
            if (!fresh.cellId().equals(route.cellId())) {
                response = forwarder.forward(fresh.cellUrl(), fresh.id(), request);
            }
        }
        usage.publish(new UsageEvents.ApiCall(UUID.randomUUID(), route.id(), clock.instant(), request.method(),
            response.status()));
        return response;
    }
}
