package com.cellforge.edge.billing.application;

import com.cellforge.edge.billing.domain.Subscription;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface Subscriptions {

    /** No-op when the tenant already has one. */
    void open(UUID tenantId, String planCode, boolean trialing, Instant trialEndsAt, Instant periodStart,
              Instant periodEnd);

    Optional<Subscription> find(UUID tenantId);
}
