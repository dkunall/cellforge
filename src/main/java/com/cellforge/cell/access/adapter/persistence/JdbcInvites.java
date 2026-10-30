package com.cellforge.cell.access.adapter.persistence;

import com.cellforge.cell.access.application.Invites;
import com.cellforge.cell.access.domain.Invite;
import com.cellforge.cell.access.domain.Role;
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
class JdbcInvites implements Invites {

    private static final String COLUMNS =
        "id, tenant_id, email, role, invited_by, created_at, expires_at, accepted_by, accepted_at, revoked_at";

    private final JdbcClient jdbc;

    JdbcInvites(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Invite i, String tokenHash) {
        jdbc.sql("INSERT INTO invites (id, tenant_id, email, role, token_hash, invited_by, created_at, expires_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
            .params(i.id(), i.tenantId(), i.email(), i.role().name(), tokenHash, i.invitedBy(),
                Timestamp.from(i.createdAt()), Timestamp.from(i.expiresAt()))
            .update();
    }

    @Override
    public Optional<Invite> findByTokenHash(String tokenHash) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM invites WHERE token_hash = ?").param(tokenHash)
            .query(JdbcInvites::map).optional();
    }

    @Override
    public boolean markAccepted(UUID id, String userId, Instant at) {
        return jdbc.sql("UPDATE invites SET accepted_by = ?, accepted_at = ? "
                + "WHERE id = ? AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at > ?")
            .params(userId, Timestamp.from(at), id, Timestamp.from(at)).update() == 1;
    }

    @Override
    public boolean revoke(UUID id, Instant at) {
        return jdbc.sql("UPDATE invites SET revoked_at = ? WHERE id = ? AND accepted_at IS NULL AND revoked_at IS NULL")
            .params(Timestamp.from(at), id).update() == 1;
    }

    @Override
    public List<Invite> open(Instant now) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM invites WHERE accepted_at IS NULL AND revoked_at IS NULL "
                + "AND expires_at > ? ORDER BY created_at")
            .param(Timestamp.from(now)).query(JdbcInvites::map).list();
    }

    private static Invite map(ResultSet rs, int n) throws SQLException {
        return new Invite(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("email"),
            Role.valueOf(rs.getString("role")), rs.getString("invited_by"), instant(rs, "created_at"),
            instant(rs, "expires_at"), rs.getString("accepted_by"), instant(rs, "accepted_at"), instant(rs, "revoked_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
