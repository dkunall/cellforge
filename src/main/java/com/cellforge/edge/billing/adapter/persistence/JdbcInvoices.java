package com.cellforge.edge.billing.adapter.persistence;

import static com.cellforge.edge.billing.adapter.persistence.JdbcSubscriptions.ts;

import com.cellforge.edge.billing.application.Invoices;
import com.cellforge.edge.billing.domain.Invoice;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcInvoices implements Invoices {

    private final JdbcClient jdbc;

    JdbcInvoices(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(Invoice i) {
        return jdbc.sql("INSERT INTO invoices (id, tenant_id, kind, plan_code, period_start, period_end, seats, calls, "
                + "seat_cents, usage_cents, credit_cents, total_cents, status, attempts, created_at, paid_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")
            .params(i.id(), i.tenantId(), i.kind().name(), i.planCode(), ts(i.periodStart()), ts(i.periodEnd()),
                i.seats(), i.calls(), i.seatCents(), i.usageCents(), i.creditCents(), i.totalCents(), i.status().name(),
                i.attempts(), ts(i.createdAt()), ts(i.paidAt()))
            .update() == 1;
    }

    @Override
    public Optional<Invoice> find(UUID id) {
        return jdbc.sql("SELECT * FROM invoices WHERE id = ?").param(id).query(JdbcInvoices::map).optional();
    }

    @Override
    public List<Invoice> forTenant(UUID tenantId, int limit) {
        return jdbc.sql("SELECT * FROM invoices WHERE tenant_id = ? ORDER BY created_at DESC, period_start DESC LIMIT ?")
            .params(tenantId, limit).query(JdbcInvoices::map).list();
    }

    @Override
    public List<Invoice> failed(UUID tenantId) {
        return jdbc.sql("SELECT * FROM invoices WHERE tenant_id = ? AND status = 'FAILED' ORDER BY created_at")
            .param(tenantId).query(JdbcInvoices::map).list();
    }

    @Override
    public long unpaid(UUID tenantId) {
        return jdbc.sql("SELECT count(*) FROM invoices WHERE tenant_id = ? AND status <> 'PAID'")
            .param(tenantId).query(Long.class).single();
    }

    @Override
    public int nextAttempt(UUID id) {
        return jdbc.sql("UPDATE invoices SET attempts = attempts + 1 WHERE id = ? RETURNING attempts")
            .param(id).query(Integer.class).single();
    }

    @Override
    public void markPaid(UUID id, Instant at) {
        jdbc.sql("UPDATE invoices SET status = 'PAID', paid_at = ? WHERE id = ?").params(ts(at), id).update();
    }

    @Override
    public void markFailed(UUID id) {
        jdbc.sql("UPDATE invoices SET status = 'FAILED' WHERE id = ? AND status <> 'PAID'").param(id).update();
    }

    private static Invoice map(ResultSet rs, int n) throws SQLException {
        Timestamp paid = rs.getTimestamp("paid_at");
        return new Invoice(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
            Invoice.Kind.valueOf(rs.getString("kind")), rs.getString("plan_code"),
            rs.getTimestamp("period_start").toInstant(), rs.getTimestamp("period_end").toInstant(), rs.getInt("seats"),
            rs.getLong("calls"), rs.getLong("seat_cents"), rs.getLong("usage_cents"), rs.getLong("credit_cents"),
            rs.getLong("total_cents"), Invoice.Status.valueOf(rs.getString("status")), rs.getInt("attempts"),
            rs.getTimestamp("created_at").toInstant(), paid == null ? null : paid.toInstant());
    }
}
