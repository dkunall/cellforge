package com.cellforge.cell.access.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEntry(UUID id, String actorId, String action, String target, Map<String, Object> details, Instant at) {
}
