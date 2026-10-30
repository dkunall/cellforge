package com.cellforge.cell.work.adapter.persistence;

import com.cellforge.cell.work.application.Comments;
import com.cellforge.cell.work.domain.Comment;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcComments implements Comments {

    private final JdbcClient jdbc;

    JdbcComments(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Comment c) {
        jdbc.sql("INSERT INTO comments (id, tenant_id, task_id, author_id, body, created_at) "
                + "VALUES (?, current_tenant(), ?, ?, ?, ?)")
            .params(c.id(), c.taskId(), c.authorId(), c.body(), Timestamp.from(c.createdAt())).update();
    }

    @Override
    public List<Comment> forTask(UUID taskId) {
        return jdbc.sql("SELECT id, task_id, author_id, body, created_at FROM comments WHERE task_id = ? "
                + "ORDER BY created_at, id")
            .param(taskId)
            .query((rs, n) -> new Comment(rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class),
                rs.getString("author_id"), rs.getString("body"), rs.getTimestamp("created_at").toInstant()))
            .list();
    }
}
