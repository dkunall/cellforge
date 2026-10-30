package com.cellforge.edge.billing.application;

import com.cellforge.edge.billing.domain.Plan;
import com.cellforge.edge.billing.domain.Subscription;
import com.cellforge.edge.directory.application.TenantLifecycle;
import com.cellforge.shared.error.InvalidRequestException;
import com.cellforge.shared.error.NotFoundException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Who pays for what. Also answers "what is this tenant allowed" for the gateway and the cells, so every limit
 * traces back to one subscription row.
 */
@Service
public class Accounts implements TenantLifecycle {

    public record Standing(Plan plan, Subscription subscription) {
    }

    /** Billing periods are a fixed 30 days rather than calendar months; it keeps proration arithmetic exact. */
    public static final Duration PERIOD = Duration.ofDays(30);

    private final Plans plans;
    private final Subscriptions subscriptions;
    private final Clock clock;
    private final EntitlementEvents events;
    private final Cache<UUID, Standing> standings = Caffeine.newBuilder()
        .maximumSize(100_000).expireAfterWrite(Duration.ofSeconds(30)).build();

    public Accounts(Plans plans, Subscriptions subscriptions, Clock clock, EntitlementEvents events) {
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.clock = clock;
        this.events = events;
    }

    @Override
    public void opened(UUID tenantId, String planCode) {
        Plan plan = plans.find(planCode)
            .orElseThrow(() -> new InvalidRequestException("unknown-plan", "No plan called " + planCode));
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        boolean trialing = plan.trialDays() > 0;
        subscriptions.open(tenantId, plan.code(), trialing, trialing ? now.plus(Duration.ofDays(plan.trialDays())) : null,
            now, now.plus(PERIOD));
    }

    public Standing standing(UUID tenantId) {
        return standings.get(tenantId, id -> {
            Subscription subscription = subscriptions.find(id)
                .orElseThrow(() -> new NotFoundException("subscription-not-found", "No subscription for tenant " + id));
            return new Standing(plans.find(subscription.planCode()).orElseThrow(), subscription);
        });
    }

    /** Drops this replica's copy only; used when another replica announced the change. */
    public void forget(UUID tenantId) {
        standings.invalidate(tenantId);
    }

    /** Call after committing a plan or status change. */
    public void changed(UUID tenantId) {
        forget(tenantId);
        events.changed(tenantId);
    }
}
