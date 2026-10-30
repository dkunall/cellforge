package com.cellforge.edge.billing.domain;

import java.time.Duration;
import java.util.List;
import java.util.Set;

public record Plan(String code, String name, int seatPriceCents, int trialDays, int maxSeats, int maxProjects,
                   int requestsPerMinute, int burst, long monthlyApiQuota, Duration statementTimeout, int maxConcurrent,
                   Set<String> features, List<ApiTier> apiTiers) {

    /** One band of graduated API pricing; {@code upTo} is null for the last, open-ended band. */
    public record ApiTier(Long upTo, int centsPer1000) {
    }
}
