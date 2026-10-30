package com.cellforge.cell.work.domain;

import java.time.Instant;
import java.util.UUID;

public record Notification(UUID id, String userId, Kind kind, UUID taskId, String actorId, Instant createdAt) {

    public enum Kind {
        MENTIONED, ASSIGNED
    }
}
