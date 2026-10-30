package com.cellforge.cell.work.application;

import com.cellforge.cell.work.domain.Activity;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface ActivityLog {

    void record(UUID projectId, UUID taskId, String actorId, String kind, Map<String, Object> details);

    List<Activity> latest(UUID projectId, int limit);
}
