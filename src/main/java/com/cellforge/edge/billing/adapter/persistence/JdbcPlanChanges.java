package com.cellforge.edge.billing.adapter.persistence;

import static com.cellforge.edge.billing.adapter.persistence.JdbcSubscriptions.ts;

import com.cellforge.edge.billing.application.PlanChanges;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPlanChanges implements PlanChanges {

    private final JdbcClient jdbc;

    JdbcPlanChanges(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Change> find(UUID tenantId, String key) {
        return jdbc.sql("SELECT * FROM plan_changes WHERE tenant_id = ? AND idempotency_key = ?").params(tenantId, key)
            .query((rs, n) -> new Change(rs.getString("request_hash"), rs.getString("from_plan"), rs.getString("to_plan"),
                rs.getLong("prorated_cents"), rs.getObject("invoice_id", UUID.class), rs.getTimestamp("at").toInstant()))
            .optional();
    }

    @Override
    public void insert(UUID tenantId, String key, Change c) {
        jdbc.sql("INSERT INTO plan_changes (tenant_id, idempotency_key, request_hash, from_plan, to_plan, prorated_cents, "
                + "invoice_id, at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
            .params(tenantId, key, c.requestHash(), c.fromPlan(), c.toPlan(), c.proratedCents(), c.invoiceId(), ts(c.at()))
            .update();
    }
}
