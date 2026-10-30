package com.cellforge.cell.access.domain;

import java.time.Instant;
import java.util.UUID;

public record Invite(UUID id, UUID tenantId, String email, Role role, String invitedBy, Instant createdAt,
                     Instant expiresAt, String acceptedBy, Instant acceptedAt, Instant revokedAt) {

    public boolean openAt(Instant now) {
        return acceptedAt == null && revokedAt == null && now.isBefore(expiresAt);
    }
}
