package com.cellforge.cell.work.adapter.persistence;

import com.cellforge.cell.work.application.Tasks;
import com.cellforge.cell.work.domain.Task;
import com.cellforge.cell.work.domain.TaskStatus;
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
class JdbcTasks implements Tasks {

    private static final String COLUMNS = "id, tenant_id, project_id, number, title, description, status, rank, "
        + "assignee_id, created_by, created_at, updated_at, version";

    private final JdbcClient jdbc;

    JdbcTasks(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Task t) {
        jdbc.sql("INSERT INTO tasks (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
            .params(t.id(), t.tenantId(), t.projectId(), t.number(), t.title(), t.description(), t.status().name(),
                t.rank(), t.assigneeId(), t.createdBy(), Timestamp.from(t.createdAt()), Timestamp.from(t.updatedAt()),
                t.version())
            .update();
    }

    @Override
    public Optional<Task> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM tasks WHERE id = ?").param(id).query(JdbcTasks::map).optional();
    }

    @Override
    public List<Task> board(UUID projectId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM tasks WHERE project_id = ? ORDER BY status, rank")
            .param(projectId).query(JdbcTasks::map).list();
    }

    @Override
    public List<Task> column(UUID projectId, TaskStatus status) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM tasks WHERE project_id = ? AND status = ? ORDER BY rank")
            .params(projectId, status.name()).query(JdbcTasks::map).list();
    }

    @Override
    public Optional<String> rankAfter(UUID projectId, TaskStatus status, String rank) {
        return jdbc.sql("SELECT rank FROM tasks WHERE project_id = ? AND status = ? AND (?::text IS NULL OR rank > ?) "
                + "ORDER BY rank LIMIT 1")
            .params(projectId, status.name(), rank, rank).query(String.class).optional();
    }

    @Override
    public Optional<String> lastRank(UUID projectId, TaskStatus status) {
        return jdbc.sql("SELECT rank FROM tasks WHERE project_id = ? AND status = ? ORDER BY rank DESC LIMIT 1")
            .params(projectId, status.name()).query(String.class).optional();
    }

    @Override
    public boolean update(Task t, int expectedVersion) {
        return jdbc.sql("UPDATE tasks SET title = ?, description = ?, assignee_id = ?, updated_at = ?, "
                + "version = version + 1 WHERE id = ? AND version = ?")
            .params(t.title(), t.description(), t.assigneeId(), Timestamp.from(t.updatedAt()), t.id(), expectedVersion)
            .update() == 1;
    }

    @Override
    public void move(UUID id, TaskStatus status, String rank, Instant at) {
        jdbc.sql("UPDATE tasks SET status = ?, rank = ?, updated_at = ?, version = version + 1 WHERE id = ?")
            .params(status.name(), rank, Timestamp.from(at), id).update();
    }

    @Override
    public void setRank(UUID id, String rank) {
        jdbc.sql("UPDATE tasks SET rank = ? WHERE id = ?").params(rank, id).update();
    }

    @Override
    public void deferRankUniqueness() {
        jdbc.sql("SET CONSTRAINTS tasks_rank_unique DEFERRED").update();
    }

    @Override
    public boolean delete(UUID id) {
        return jdbc.sql("DELETE FROM tasks WHERE id = ?").param(id).update() == 1;
    }

    @Override
    public List<Task> openAssignedTo(String userId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM tasks WHERE assignee_id = ? AND status <> 'DONE' "
                + "ORDER BY updated_at DESC LIMIT ?")
            .params(userId, limit).query(JdbcTasks::map).list();
    }

    private static Task map(ResultSet rs, int n) throws SQLException {
        return new Task(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
            rs.getObject("project_id", UUID.class), rs.getInt("number"), rs.getString("title"),
            rs.getString("description"), TaskStatus.valueOf(rs.getString("status")), rs.getString("rank"),
            rs.getString("assignee_id"), rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(), rs.getInt("version"));
    }
}
