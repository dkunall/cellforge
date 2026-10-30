package com.cellforge.edge.billing.adapter.persistence;

import com.cellforge.edge.billing.application.Subscriptions;
import com.cellforge.edge.billing.domain.Subscription;
import com.cellforge.edge.billing.domain.SubscriptionStatus;
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
class JdbcSubscriptions implements Subscriptions {

    private final JdbcClient jdbc;

    JdbcSubscriptions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void open(UUID tenantId, String planCode, boolean trialing, Instant trialEndsAt, Instant periodStart,
                     Instant periodEnd) {
        jdbc.sql("INSERT INTO subscriptions (tenant_id, plan_code, status, trial_ends_at, period_start, period_end) "
                + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id) DO NOTHING")
            .params(tenantId, planCode, trialing ? "TRIALING" : "ACTIVE", ts(trialEndsAt), ts(periodStart), ts(periodEnd))
            .update();
    }

    @Override
    public Optional<Subscription> find(UUID tenantId) {
        return jdbc.sql("SELECT * FROM subscriptions WHERE tenant_id = ?").param(tenantId)
            .query(JdbcSubscriptions::map).optional();
    }

    @Override
    public Optional<Subscription> lock(UUID tenantId) {
        return jdbc.sql("SELECT * FROM subscriptions WHERE tenant_id = ? FOR UPDATE").param(tenantId)
            .query(JdbcSubscriptions::map).optional();
    }

    @Override
    public void save(Subscription s) {
        int rows = jdbc.sql("UPDATE subscriptions SET plan_code = ?, status = ?, period_start = ?, period_end = ?, "
                + "grace_until = ?, card_last4 = ?, credit_cents = ?, version = version + 1 "
                + "WHERE tenant_id = ? AND version = ?")
            .params(s.planCode(), s.status().name(), ts(s.periodStart()), ts(s.periodEnd()), ts(s.graceUntil()),
                s.cardLast4(), s.creditCents(), s.tenantId(), s.version())
            .update();
        if (rows != 1) {
            // Callers hold the row lock, so this means someone wrote without it.
            throw new IllegalStateException("Subscription " + s.tenantId() + " changed underneath a locked update");
        }
    }

    @Override
    public List<UUID> due(Instant now, int limit) {
        return jdbc.sql("SELECT tenant_id FROM subscriptions WHERE (status = 'TRIALING' AND trial_ends_at <= ?) "
                + "OR (status IN ('ACTIVE', 'PAST_DUE') AND period_end <= ?) ORDER BY period_end LIMIT ?")
            .params(ts(now), ts(now), limit).query(UUID.class).list();
    }

    @Override
    public List<UUID> graceExpired(Instant now, int limit) {
        return jdbc.sql("SELECT tenant_id FROM subscriptions WHERE status = 'PAST_DUE' AND grace_until <= ? LIMIT ?")
            .params(ts(now), limit).query(UUID.class).list();
    }

    static Subscription map(ResultSet rs, int n) throws SQLException {
        return new Subscription(rs.getObject("tenant_id", UUID.class), rs.getString("plan_code"),
            SubscriptionStatus.valueOf(rs.getString("status")), instant(rs.getTimestamp("trial_ends_at")),
            instant(rs.getTimestamp("period_start")), instant(rs.getTimestamp("period_end")),
            instant(rs.getTimestamp("grace_until")), rs.getString("card_last4"), rs.getLong("credit_cents"), rs.getInt("version"));
    }

    static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
