package com.cellforge.cell.access.adapter.persistence;

import com.cellforge.cell.access.application.Members;
import com.cellforge.cell.access.domain.Member;
import com.cellforge.cell.access.domain.Role;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcMembers implements Members {

    private static final String COLUMNS = "tenant_id, user_id, email, display_name, role, joined_at";

    private final JdbcClient jdbc;

    JdbcMembers(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Member> find(String userId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM memberships WHERE user_id = ?").param(userId)
            .query(JdbcMembers::map).optional();
    }

    @Override
    public List<Member> all() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM memberships ORDER BY joined_at, user_id").query(JdbcMembers::map).list();
    }

    @Override
    public void insert(Member m) {
        jdbc.sql("INSERT INTO memberships (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?)")
            .params(m.tenantId(), m.userId(), m.email(), m.displayName(), m.role().name(), Timestamp.from(m.joinedAt()))
            .update();
    }

    @Override
    public boolean updateRole(String userId, Role role) {
        return jdbc.sql("UPDATE memberships SET role = ? WHERE user_id = ?").params(role.name(), userId).update() == 1;
    }

    @Override
    public boolean delete(String userId) {
        return jdbc.sql("DELETE FROM memberships WHERE user_id = ?").param(userId).update() == 1;
    }

    @Override
    public long seats() {
        return jdbc.sql("SELECT count(*) FROM memberships WHERE role <> 'GUEST'").query(Long.class).single();
    }

    @Override
    public long countByRole(Role role) {
        return jdbc.sql("SELECT count(*) FROM memberships WHERE role = ?").param(role.name()).query(Long.class).single();
    }

    @Override
    public void lockSeats() {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('seats:' || current_tenant()::text, 0))").query().singleRow();
    }

    private static Member map(ResultSet rs, int n) throws SQLException {
        return new Member(rs.getObject("tenant_id", UUID.class), rs.getString("user_id"), rs.getString("email"),
            rs.getString("display_name"), Role.valueOf(rs.getString("role")), rs.getTimestamp("joined_at").toInstant());
    }
}
