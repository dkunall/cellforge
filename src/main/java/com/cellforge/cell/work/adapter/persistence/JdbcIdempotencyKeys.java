package com.cellforge.cell.work.adapter.persistence;

import com.cellforge.cell.work.application.IdempotencyKeys;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcIdempotencyKeys implements IdempotencyKeys {

    private final JdbcClient jdbc;
    private final Clock clock;

    JdbcIdempotencyKeys(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Optional<Stored> claim(String userId, String key, String requestHash) {
        int inserted = jdbc.sql("INSERT INTO idempotency_keys (tenant_id, user_id, key, request_hash, created_at) "
                + "VALUES (current_tenant(), ?, ?, ?, ?) ON CONFLICT DO NOTHING")
            .params(userId, key, requestHash, Timestamp.from(clock.instant())).update();
        if (inserted == 1) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT request_hash, status, body::text AS body FROM idempotency_keys "
                + "WHERE tenant_id = current_tenant() AND user_id = ? AND key = ?")
            .params(userId, key)
            .query((rs, n) -> new Stored(rs.getString("request_hash"), (Integer) rs.getObject("status"),
                rs.getString("body")))
            .optional();
    }

    @Override
    public void complete(String userId, String key, int status, String body) {
        jdbc.sql("UPDATE idempotency_keys SET status = ?, body = ?::jsonb "
                + "WHERE tenant_id = current_tenant() AND user_id = ? AND key = ?")
            .params(status, body, userId, key).update();
    }
}
