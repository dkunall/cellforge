package com.cellforge.cell.work.adapter.persistence;

import com.cellforge.cell.work.application.ActivityLog;
import com.cellforge.cell.work.domain.Activity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcActivityLog implements ActivityLog {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    JdbcActivityLog(JdbcClient jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void record(UUID projectId, UUID taskId, String actorId, String kind, Map<String, Object> details) {
        jdbc.sql("INSERT INTO activity (tenant_id, project_id, task_id, actor_id, kind, details, at) "
                + "VALUES (current_tenant(), ?, ?, ?, ?, ?::jsonb, ?)")
            .params(projectId, taskId, actorId, kind, write(details), Timestamp.from(clock.instant())).update();
    }

    @Override
    public List<Activity> latest(UUID projectId, int limit) {
        return jdbc.sql("SELECT id, project_id, task_id, actor_id, kind, details, at FROM activity "
                + "WHERE project_id = ? ORDER BY id DESC LIMIT ?")
            .params(projectId, limit)
            .query((rs, n) -> new Activity(rs.getLong("id"), rs.getObject("project_id", UUID.class),
                rs.getObject("task_id", UUID.class), rs.getString("actor_id"), rs.getString("kind"),
                read(rs.getString("details")), rs.getTimestamp("at").toInstant()))
            .list();
    }

    private String write(Map<String, Object> details) {
        try {
            return json.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private Map<String, Object> read(String details) {
        try {
            return json.readValue(details, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
