package com.cellforge.cell.access.adapter.persistence;

import com.cellforge.cell.access.application.AuditLog;
import com.cellforge.cell.access.domain.AuditEntry;
import com.cellforge.cell.tenancy.application.TenantContext;
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
class JdbcAuditLog implements AuditLog {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    JdbcAuditLog(JdbcClient jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void record(String actorId, String action, String target, Map<String, Object> details) {
        jdbc.sql("INSERT INTO audit_log (id, tenant_id, actor_id, action, target, details, at) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)")
            .params(UUID.randomUUID(), TenantContext.tenantId(), actorId, action, target, write(details),
                Timestamp.from(clock.instant()))
            .update();
    }

    @Override
    public List<AuditEntry> latest(int limit) {
        return jdbc.sql("SELECT id, actor_id, action, target, details::text AS details, at FROM audit_log ORDER BY at DESC, id LIMIT ?")
            .param(limit)
            .query((rs, n) -> new AuditEntry(rs.getObject("id", UUID.class), rs.getString("actor_id"), rs.getString("action"),
                rs.getString("target"), read(rs.getString("details")), rs.getTimestamp("at").toInstant()))
            .list();
    }

    private String write(Map<String, Object> details) {
        try {
            return json.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private Map<String, Object> read(String text) {
        try {
            return json.readValue(text, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
