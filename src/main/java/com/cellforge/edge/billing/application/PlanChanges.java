package com.cellforge.edge.billing.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PlanChanges {

    record Change(String requestHash, String fromPlan, String toPlan, long proratedCents, UUID invoiceId, Instant at) {
    }

    Optional<Change> find(UUID tenantId, String key);

    void insert(UUID tenantId, String key, Change change);
}
