package com.cellforge.cell.work.adapter.persistence;

import com.cellforge.cell.work.application.Projects;
import com.cellforge.cell.work.domain.Project;
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
class JdbcProjects implements Projects {

    private static final String COLUMNS = "id, tenant_id, key, name, created_by, created_at, archived_at, board_version";
    private static final String P_COLUMNS = "p.id, p.tenant_id, p.key, p.name, p.created_by, p.created_at, "
        + "p.archived_at, p.board_version";

    private final JdbcClient jdbc;

    JdbcProjects(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Project p) {
        jdbc.sql("INSERT INTO projects (id, tenant_id, key, name, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?)")
            .params(p.id(), p.tenantId(), p.key(), p.name(), p.createdBy(), Timestamp.from(p.createdAt()))
            .update();
    }

    @Override
    public Optional<Project> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM projects WHERE id = ?").param(id).query(JdbcProjects::map).optional();
    }

    @Override
    public Optional<Project> lockForChange(UUID id) {
        return jdbc.sql("UPDATE projects SET board_version = board_version + 1 WHERE id = ? RETURNING " + COLUMNS)
            .param(id).query(JdbcProjects::map).optional();
    }

    @Override
    public int takeTaskNumber(UUID id) {
        return jdbc.sql("UPDATE projects SET next_task_number = next_task_number + 1 WHERE id = ? "
                + "RETURNING next_task_number - 1")
            .param(id).query(Integer.class).single();
    }

    @Override
    public List<Project> all() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM projects ORDER BY key").query(JdbcProjects::map).list();
    }

    @Override
    public List<Project> openToGuest(String userId) {
        return jdbc.sql("SELECT " + P_COLUMNS + " FROM projects p "
                + "JOIN project_guests g ON g.project_id = p.id WHERE g.user_id = ? ORDER BY p.key")
            .param(userId).query(JdbcProjects::map).list();
    }

    @Override
    public long countActive() {
        return jdbc.sql("SELECT count(*) FROM projects WHERE archived_at IS NULL").query(Long.class).single();
    }

    @Override
    public void lockQuota() {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('projects:' || current_tenant()::text, 0))")
            .query().singleRow();
    }

    @Override
    public void archive(UUID id, Instant at) {
        jdbc.sql("UPDATE projects SET archived_at = ? WHERE id = ? AND archived_at IS NULL")
            .params(Timestamp.from(at), id).update();
    }

    @Override
    public boolean isGuest(UUID projectId, String userId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM project_guests WHERE project_id = ? AND user_id = ?)")
            .params(projectId, userId).query(Boolean.class).single();
    }

    @Override
    public void addGuest(UUID projectId, String userId) {
        jdbc.sql("INSERT INTO project_guests (tenant_id, project_id, user_id) VALUES (current_tenant(), ?, ?) "
                + "ON CONFLICT DO NOTHING")
            .params(projectId, userId).update();
    }

    @Override
    public boolean removeGuest(UUID projectId, String userId) {
        return jdbc.sql("DELETE FROM project_guests WHERE project_id = ? AND user_id = ?")
            .params(projectId, userId).update() == 1;
    }

    private static Project map(ResultSet rs, int n) throws SQLException {
        Timestamp archived = rs.getTimestamp("archived_at");
        return new Project(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("key"),
            rs.getString("name"), rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(),
            archived == null ? null : archived.toInstant(), rs.getLong("board_version"));
    }
}
