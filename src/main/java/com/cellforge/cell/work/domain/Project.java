package com.cellforge.cell.work.domain;

import java.time.Instant;
import java.util.UUID;

public record Project(UUID id, UUID tenantId, String key, String name, String createdBy, Instant createdAt,
                      Instant archivedAt, long boardVersion) {

    public boolean archived() {
        return archivedAt != null;
    }
}
