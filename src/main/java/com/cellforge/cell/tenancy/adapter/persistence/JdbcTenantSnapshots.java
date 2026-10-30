package com.cellforge.cell.tenancy.adapter.persistence;

import com.cellforge.cell.tenancy.application.TenantSnapshots;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Generic over the tenant tables: rows travel as jsonb and come back through jsonb_populate_recordset, so a new
 * column needs no code here, only a new table does. RLS limits every statement to the current tenant.
 */
@Repository
class JdbcTenantSnapshots implements TenantSnapshots {

    // Parents before children.
    static final List<String> TABLES = List.of("tenants", "memberships", "invites", "audit_log", "projects",
        "project_guests", "tasks", "comments", "activity", "notifications", "idempotency_keys");
    // Activity ids come from this cell's sequence; copying them would collide with the target's own.
    private static final String RENUMBERED = "activity";

    private final JdbcClient jdbc;

    JdbcTenantSnapshots(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, String> export() {
        utc();
        Map<String, String> out = new LinkedHashMap<>();
        for (String table : TABLES) {
            String order = table.equals(RENUMBERED) ? " ORDER BY t.id" : "";
            out.put(table, jdbc.sql("SELECT coalesce(jsonb_agg(to_jsonb(t)" + order + "), '[]')::text FROM " + table + " t")
                .query(String.class).single());
        }
        return out;
    }

    @Override
    public void restore(Map<String, String> tables) {
        for (String table : TABLES) {
            String rows = tables.get(table);
            if (rows == null) {
                throw new IllegalArgumentException("Snapshot has no " + table);
            }
            if (table.equals(RENUMBERED)) {
                jdbc.sql("INSERT INTO activity (tenant_id, project_id, task_id, actor_id, kind, details, at) "
                        + "SELECT tenant_id, project_id, task_id, actor_id, kind, details, at "
                        + "FROM jsonb_populate_recordset(null::activity, ?::jsonb) ORDER BY id")
                    .param(rows).update();
            } else {
                jdbc.sql("INSERT INTO " + table + " SELECT * FROM jsonb_populate_recordset(null::" + table + ", ?::jsonb)")
                    .param(rows).update();
            }
        }
    }

    @Override
    public Map<String, TableDigest> digest() {
        utc();
        Map<String, TableDigest> out = new LinkedHashMap<>();
        for (String table : TABLES) {
            String row = table.equals(RENUMBERED) ? "(to_jsonb(t) - 'id')" : "to_jsonb(t)";
            out.put(table, jdbc.sql("SELECT count(*) AS n, coalesce(md5(string_agg(r, '|' ORDER BY r)), '') AS h "
                    + "FROM (SELECT " + row + "::text AS r FROM " + table + " t) x")
                .query((rs, i) -> new TableDigest(rs.getLong("n"), rs.getString("h"))).single());
        }
        return out;
    }

    // Timestamps render in the session time zone; pin it so both cells print the same text.
    private void utc() {
        jdbc.sql("SELECT set_config('TimeZone', 'UTC', true)").query(String.class).single();
    }
}
