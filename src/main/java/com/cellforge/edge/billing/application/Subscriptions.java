package com.cellforge.edge.billing.application;

import com.cellforge.edge.billing.domain.Subscription;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface Subscriptions {

    /** No-op when the tenant already has one. */
    void open(UUID tenantId, String planCode, boolean trialing, Instant trialEndsAt, Instant periodStart,
              Instant periodEnd);

    Optional<Subscription> find(UUID tenantId);

    /** Row-locks the subscription until the transaction ends. */
    Optional<Subscription> lock(UUID tenantId);

    /** Writes every mutable field; the version must still match. */
    void save(Subscription changed);

    /** Trials that have ended and periods that have closed. */
    List<UUID> due(Instant now, int limit);

    List<UUID> graceExpired(Instant now, int limit);
}
