package com.cellforge.edge.billing.adapter.persistence;

import com.cellforge.edge.billing.application.Subscriptions;
import com.cellforge.edge.billing.domain.Subscription;
import com.cellforge.edge.billing.domain.SubscriptionStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
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

    static Subscription map(ResultSet rs, int n) throws SQLException {
        return new Subscription(rs.getObject("tenant_id", UUID.class), rs.getString("plan_code"),
            SubscriptionStatus.valueOf(rs.getString("status")), instant(rs.getTimestamp("trial_ends_at")),
            instant(rs.getTimestamp("period_start")), instant(rs.getTimestamp("period_end")),
            instant(rs.getTimestamp("grace_until")), rs.getString("card_last4"), rs.getInt("version"));
    }

    static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
