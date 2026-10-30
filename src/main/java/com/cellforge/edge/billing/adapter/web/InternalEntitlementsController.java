package com.cellforge.edge.billing.adapter.web;

import com.cellforge.edge.billing.application.Accounts;
import com.cellforge.edge.billing.domain.Plan;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** What a cell needs to enforce next to the data. Rate limits and quotas stay at the edge. */
@RestController
class InternalEntitlementsController {

    record EntitlementsView(String plan, Set<String> features, int maxSeats, int maxProjects, long statementTimeoutMs,
                            int maxConcurrentRequests) {
    }

    private final Accounts accounts;

    InternalEntitlementsController(Accounts accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/internal/entitlements/{tenantId}")
    EntitlementsView entitlements(@PathVariable UUID tenantId) {
        Plan plan = accounts.standing(tenantId).plan();
        return new EntitlementsView(plan.code(), plan.features(), plan.maxSeats(), plan.maxProjects(),
            plan.statementTimeout().toMillis(), plan.maxConcurrent());
    }
}
