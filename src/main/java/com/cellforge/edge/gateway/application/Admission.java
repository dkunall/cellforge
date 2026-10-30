package com.cellforge.edge.gateway.application;

import com.cellforge.edge.billing.domain.Plan;
import java.util.UUID;

/** Per-tenant rate limit and monthly API quota. */
public interface Admission {

    enum Outcome { ADMITTED, RATE_LIMITED, QUOTA_EXHAUSTED }

    record Decision(Outcome outcome, long retryAfterMillis) {
    }

    Decision admit(UUID tenantId, Plan plan);
}
