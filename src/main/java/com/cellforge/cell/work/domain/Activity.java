package com.cellforge.cell.work.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record Activity(long id, UUID projectId, UUID taskId, String actorId, String kind, Map<String, Object> details,
                       Instant at) {
}
