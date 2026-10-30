package com.cellforge.edge.billing.domain;

import java.time.Instant;
import java.util.UUID;

public record Invoice(UUID id, UUID tenantId, Kind kind, String planCode, Instant periodStart, Instant periodEnd,
                      int seats, long calls, long seatCents, long usageCents, long creditCents, long totalCents,
                      Status status, int attempts, Instant createdAt, Instant paidAt) {

    public enum Kind { PERIOD, PRORATION }

    public enum Status { OPEN, PAID, FAILED }
}
