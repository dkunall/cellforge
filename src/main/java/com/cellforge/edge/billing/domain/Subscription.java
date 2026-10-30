package com.cellforge.edge.billing.domain;

import java.time.Instant;
import java.util.UUID;

public record Subscription(UUID tenantId, String planCode, SubscriptionStatus status, Instant trialEndsAt,
                           Instant periodStart, Instant periodEnd, Instant graceUntil, String cardLast4, int version) {
}
