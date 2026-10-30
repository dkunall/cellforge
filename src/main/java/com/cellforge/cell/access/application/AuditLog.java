package com.cellforge.cell.access.application;

import com.cellforge.cell.access.domain.AuditEntry;
import java.util.List;
import java.util.Map;

public interface AuditLog {

    void record(String actorId, String action, String target, Map<String, Object> details);

    List<AuditEntry> latest(int limit);
}
