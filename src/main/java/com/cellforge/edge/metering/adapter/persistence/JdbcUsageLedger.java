package com.cellforge.edge.metering.adapter.persistence;

import com.cellforge.edge.metering.application.UsageLedger;
import com.cellforge.edge.metering.domain.UsageEvent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcUsageLedger implements UsageLedger {

    private final JdbcClient jdbc;

    JdbcUsageLedger(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean recordOnce(UsageEvent event) {
        return jdbc.sql("INSERT INTO usage_events (event_id, tenant_id, at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")
            .params(event.eventId(), event.tenantId(), Timestamp.from(event.at())).update() == 1;
    }

    @Override
    public void addToHour(UUID tenantId, Instant hour, long calls, long errors) {
        jdbc.sql("""
                INSERT INTO usage_hourly (tenant_id, hour, calls, errors) VALUES (?, ?, ?, ?)
                ON CONFLICT (tenant_id, hour) DO UPDATE
                SET calls = usage_hourly.calls + excluded.calls, errors = usage_hourly.errors + excluded.errors
                """)
            .params(tenantId, Timestamp.from(hour), calls, errors).update();
    }

    @Override
    public long callsBetween(UUID tenantId, Instant from, Instant to) {
        return jdbc.sql("SELECT coalesce(sum(calls), 0) FROM usage_hourly WHERE tenant_id = ? AND hour >= ? AND hour < ?")
            .params(tenantId, Timestamp.from(from), Timestamp.from(to)).query(Long.class).single();
    }

    @Override
    public int forgetEventsBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM usage_events WHERE at < ?").param(Timestamp.from(cutoff)).update();
    }
}
