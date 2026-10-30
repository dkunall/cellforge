package com.cellforge.cell.work.domain;

import java.time.Instant;
import java.util.UUID;

public record Task(UUID id, UUID tenantId, UUID projectId, int number, String title, String description,
                   TaskStatus status, String rank, String assigneeId, String createdBy, Instant createdAt,
                   Instant updatedAt, int version) {
}
