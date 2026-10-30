package com.cellforge.cell.work.domain;

import java.time.Instant;
import java.util.UUID;

public record Comment(UUID id, UUID taskId, String authorId, String body, Instant createdAt) {
}
