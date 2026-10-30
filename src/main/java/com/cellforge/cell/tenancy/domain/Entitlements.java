package com.cellforge.cell.tenancy.domain;

import java.time.Duration;
import java.util.Set;

/**
 * What the tenant's plan allows, as published by the edge. A cell never decides plans itself; it only enforces
 * the limits that live next to the data (seats, projects, query budget, concurrency).
 */
public record Entitlements(String plan, Set<String> features, int maxSeats, int maxProjects, Duration statementTimeout,
                           int maxConcurrentRequests) {

    public boolean has(String feature) {
        return features.contains(feature);
    }
}
