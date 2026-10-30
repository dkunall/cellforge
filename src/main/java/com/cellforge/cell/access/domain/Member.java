package com.cellforge.cell.access.domain;

import java.time.Instant;
import java.util.UUID;

public record Member(UUID tenantId, String userId, String email, String displayName, Role role, Instant joinedAt) {

    public boolean can(Permission permission) {
        return role.allows(permission);
    }
}
