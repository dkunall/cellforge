package com.cellforge.cell.work.adapter.persistence;

import com.cellforge.cell.work.application.Notifications;
import com.cellforge.cell.work.domain.Notification;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcNotifications implements Notifications {

    private final JdbcClient jdbc;

    JdbcNotifications(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Notification n) {
        jdbc.sql("INSERT INTO notifications (id, tenant_id, user_id, kind, task_id, actor_id, created_at) "
                + "VALUES (?, current_tenant(), ?, ?, ?, ?, ?)")
            .params(n.id(), n.userId(), n.kind().name(), n.taskId(), n.actorId(), Timestamp.from(n.createdAt()))
            .update();
    }

    @Override
    public List<Notification> unread(String userId, int limit) {
        return jdbc.sql("SELECT id, user_id, kind, task_id, actor_id, created_at FROM notifications "
                + "WHERE user_id = ? AND read_at IS NULL ORDER BY created_at DESC, id LIMIT ?")
            .params(userId, limit)
            .query((rs, n) -> new Notification(rs.getObject("id", UUID.class), rs.getString("user_id"),
                Notification.Kind.valueOf(rs.getString("kind")), rs.getObject("task_id", UUID.class),
                rs.getString("actor_id"), rs.getTimestamp("created_at").toInstant()))
            .list();
    }

    @Override
    public long unreadCount(String userId) {
        return jdbc.sql("SELECT count(*) FROM notifications WHERE user_id = ? AND read_at IS NULL")
            .param(userId).query(Long.class).single();
    }

    @Override
    public int markRead(String userId, Instant upTo) {
        return jdbc.sql("UPDATE notifications SET read_at = now() WHERE user_id = ? AND read_at IS NULL "
                + "AND created_at <= ?")
            .params(userId, Timestamp.from(upTo)).update();
    }
}
