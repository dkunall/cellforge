package com.cellforge.cell.work.application;

import com.cellforge.cell.access.domain.Member;
import com.cellforge.cell.tenancy.application.TenantContext;
import com.cellforge.cell.work.domain.Notification;
import com.cellforge.cell.work.domain.Task;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a member should look at: unread notifications and their open tasks. Pulled by the client rather than
 * mailed (see README).
 */
@Service
public class InboxService {

    public record Digest(Instant asOf, long unreadCount, List<Notification> unread, List<Task> assigned) {
    }

    private final Notifications notifications;
    private final Tasks tasks;
    private final Clock clock;

    public InboxService(Notifications notifications, Tasks tasks, Clock clock) {
        this.notifications = notifications;
        this.tasks = tasks;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Digest digest() {
        Member me = TenantContext.member();
        return new Digest(clock.instant(), notifications.unreadCount(me.userId()), notifications.unread(me.userId(), 50),
            tasks.openAssignedTo(me.userId(), 50));
    }

    /** Marks everything up to {@code upTo} as read, so a notification that arrived after the client looked stays unread. */
    @Transactional
    public int markRead(Instant upTo) {
        return notifications.markRead(TenantContext.member().userId(), upTo);
    }
}
