package com.cellforge.cell.work.application;

import com.cellforge.cell.access.application.Members;
import com.cellforge.cell.access.domain.Member;
import com.cellforge.cell.access.domain.Permission;
import com.cellforge.cell.access.domain.Role;
import com.cellforge.cell.tenancy.application.TenantContext;
import com.cellforge.cell.work.domain.Comment;
import com.cellforge.cell.work.domain.Mentions;
import com.cellforge.cell.work.domain.Notification;
import com.cellforge.cell.work.domain.Project;
import com.cellforge.cell.work.domain.ProjectAccess;
import com.cellforge.cell.work.domain.Rank;
import com.cellforge.cell.work.domain.Task;
import com.cellforge.cell.work.domain.TaskStatus;
import com.cellforge.shared.error.BusinessRuleException;
import com.cellforge.shared.error.InvalidRequestException;
import com.cellforge.shared.error.NotFoundException;
import com.cellforge.shared.error.PreconditionFailedException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskService {

    public record NewTask(String title, String description, TaskStatus status, String assigneeId) {
    }

    /** Null title/description mean "leave as is"; {@code assigneeSet} distinguishes "unassign" from "untouched". */
    public record TaskChanges(String title, String description, boolean assigneeSet, String assigneeId) {
    }

    public record Created(Task task, boolean replayed) {
    }

    private final Projects projects;
    private final Tasks tasks;
    private final Comments comments;
    private final Members members;
    private final ActivityLog activity;
    private final Notifications notifications;
    private final IdempotencyKeys idempotency;
    private final Clock clock;

    public TaskService(Projects projects, Tasks tasks, Comments comments, Members members, ActivityLog activity,
                       Notifications notifications, IdempotencyKeys idempotency, Clock clock) {
        this.projects = projects;
        this.tasks = tasks;
        this.comments = comments;
        this.members = members;
        this.activity = activity;
        this.notifications = notifications;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * With an idempotency key, a retry of the same request returns the task the first attempt created (as it
     * is now) instead of creating another; the key row commits or rolls back together with the task.
     */
    @Transactional
    public Created create(UUID projectId, NewTask cmd, String idempotencyKey, String requestHash) {
        Member actor = TenantContext.member();
        if (idempotencyKey != null) {
            var earlier = idempotency.claim(actor.userId(), idempotencyKey, requestHash);
            if (earlier.isPresent()) {
                if (!earlier.get().requestHash().equals(requestHash)) {
                    throw new BusinessRuleException("idempotency-key-reused",
                        "This Idempotency-Key was used for a different request");
                }
                UUID taskId = UUID.fromString(earlier.get().body());
                return new Created(tasks.find(taskId).orElseThrow(() -> taskNotFound(taskId)), true);
            }
        }
        Project project = projects.lockForChange(projectId).orElseThrow(() -> ProjectService.notFound(projectId));
        ProjectAccess.checkWrite(actor, project, false, Permission.TASK_WRITE);
        if (cmd.assigneeId() != null) {
            checkAssignable(cmd.assigneeId());
        }
        TaskStatus status = cmd.status() == null ? TaskStatus.TODO : cmd.status();
        String rank = Rank.between(tasks.lastRank(projectId, status).orElse(null), null);
        Instant now = clock.instant();
        Task task = new Task(UUID.randomUUID(), actor.tenantId(), projectId, projects.takeTaskNumber(projectId),
            cmd.title().trim(), cmd.description() == null ? "" : cmd.description(), status, rank, cmd.assigneeId(),
            actor.userId(), now, now, 1);
        tasks.insert(task);
        if (idempotencyKey != null) {
            idempotency.complete(actor.userId(), idempotencyKey, 201, task.id().toString());
        }
        activity.record(projectId, task.id(), actor.userId(), "task.created", Map.of("number", task.number()));
        notifyAssignee(task, actor, now);
        return new Created(task, false);
    }

    @Transactional(readOnly = true)
    public Task get(UUID taskId) {
        Member member = TenantContext.member();
        Task task = tasks.find(taskId).orElseThrow(() -> taskNotFound(taskId));
        Project project = projects.find(task.projectId()).orElseThrow();
        ProjectAccess.checkRead(member, project, guestOn(member, project));
        return task;
    }

    @Transactional
    public Task update(UUID taskId, int expectedVersion, TaskChanges changes) {
        Member actor = TenantContext.member();
        Task current = lockTask(taskId, actor, Permission.TASK_WRITE);
        if (current.version() != expectedVersion) {
            throw stale(current);
        }
        if (changes.assigneeSet() && changes.assigneeId() != null) {
            checkAssignable(changes.assigneeId());
        }
        Instant now = clock.instant();
        Task updated = new Task(current.id(), current.tenantId(), current.projectId(), current.number(),
            changes.title() != null ? changes.title().trim() : current.title(),
            changes.description() != null ? changes.description() : current.description(),
            current.status(), current.rank(),
            changes.assigneeSet() ? changes.assigneeId() : current.assigneeId(),
            current.createdBy(), current.createdAt(), now, current.version() + 1);
        if (!tasks.update(updated, expectedVersion)) {
            throw stale(current);
        }
        Map<String, Object> details = new HashMap<>();
        if (!updated.title().equals(current.title())) {
            details.put("title", updated.title());
        }
        if (!Objects.equals(updated.assigneeId(), current.assigneeId())) {
            details.put("assigneeId", updated.assigneeId());
            notifyAssignee(updated, actor, now);
        }
        activity.record(current.projectId(), taskId, actor.userId(), "task.updated", details);
        return updated;
    }

    /**
     * Places the task right after {@code afterId} in {@code status}'s column, or at the top when afterId is null.
     * One row changes, unless the new key would be too long, in which case the column is respread first.
     */
    @Transactional
    public Task move(UUID taskId, TaskStatus status, UUID afterId, Integer expectedVersion) {
        Member actor = TenantContext.member();
        Task task = lockTask(taskId, actor, Permission.TASK_WRITE);
        if (expectedVersion != null && task.version() != expectedVersion) {
            throw stale(task);
        }
        if (taskId.equals(afterId)) {
            throw new InvalidRequestException("invalid-position", "A task cannot be placed after itself");
        }
        String before = null;
        if (afterId != null) {
            Task anchor = tasks.find(afterId).filter(t -> t.projectId().equals(task.projectId()) && t.status() == status)
                .orElseThrow(() -> new BusinessRuleException("invalid-position", "afterId is not in the " + status + " column"));
            before = anchor.rank();
        }
        String after = tasks.rankAfter(task.projectId(), status, before).orElse(null);
        if (task.status() == status && task.rank().equals(after)) {
            return task;
        }
        String rank = Rank.between(before, after);
        if (rank.length() > Rank.REBALANCE_LENGTH) {
            rank = rebalance(task, status, afterId);
        }
        Instant now = clock.instant();
        tasks.move(taskId, status, rank, now);
        activity.record(task.projectId(), taskId, actor.userId(), "task.moved",
            Map.of("from", task.status().name(), "to", status.name()));
        return tasks.find(taskId).orElseThrow();
    }

    @Transactional
    public void delete(UUID taskId) {
        Member actor = TenantContext.member();
        Task task = lockTask(taskId, actor, Permission.TASK_WRITE);
        ProjectAccess.checkDeleteTask(actor, task);
        tasks.delete(taskId);
        activity.record(task.projectId(), null, actor.userId(), "task.deleted",
            Map.of("number", task.number(), "title", task.title()));
    }

    @Transactional
    public Comment comment(UUID taskId, String body) {
        Member actor = TenantContext.member();
        Task task = tasks.find(taskId).orElseThrow(() -> taskNotFound(taskId));
        Project project = projects.find(task.projectId()).orElseThrow();
        ProjectAccess.checkWrite(actor, project, guestOn(actor, project), Permission.COMMENT);
        Instant now = clock.instant();
        Comment comment = new Comment(UUID.randomUUID(), taskId, actor.userId(), body, now);
        comments.insert(comment);
        Set<String> handles = Mentions.in(body);
        if (!handles.isEmpty()) {
            for (Member m : members.all()) {
                boolean mentioned = handles.contains(m.userId().toLowerCase()) || handles.contains(localPart(m.email()));
                if (mentioned && !m.userId().equals(actor.userId()) && canSee(m, project)) {
                    notifications.insert(new Notification(UUID.randomUUID(), m.userId(), Notification.Kind.MENTIONED,
                        taskId, actor.userId(), now));
                }
            }
        }
        activity.record(task.projectId(), taskId, actor.userId(), "comment.added", Map.of("commentId", comment.id().toString()));
        return comment;
    }

    @Transactional(readOnly = true)
    public List<Comment> comments(UUID taskId) {
        get(taskId);
        return comments.forTask(taskId);
    }

    private Task lockTask(UUID taskId, Member actor, Permission needed) {
        Task found = tasks.find(taskId).orElseThrow(() -> taskNotFound(taskId));
        Project project = projects.lockForChange(found.projectId()).orElseThrow();
        ProjectAccess.checkWrite(actor, project, guestOn(actor, project), needed);
        // Re-read under the project lock: the first read may predate a concurrent writer that just committed.
        return tasks.find(taskId).orElseThrow(() -> taskNotFound(taskId));
    }

    private String rebalance(Task moving, TaskStatus status, UUID afterId) {
        List<Task> column = new ArrayList<>(tasks.column(moving.projectId(), status));
        column.removeIf(t -> t.id().equals(moving.id()));
        int at = 0;
        if (afterId != null) {
            for (int i = 0; i < column.size(); i++) {
                if (column.get(i).id().equals(afterId)) {
                    at = i + 1;
                }
            }
        }
        List<String> keys = Rank.spread(column.size() + 1);
        tasks.deferRankUniqueness();
        int k = 0;
        String mine = null;
        for (int i = 0; i <= column.size(); i++) {
            if (i == at) {
                mine = keys.get(k++);
            }
            if (i < column.size()) {
                tasks.setRank(column.get(i).id(), keys.get(k++));
            }
        }
        return mine;
    }

    private void checkAssignable(String userId) {
        Member assignee = members.find(userId)
            .orElseThrow(() -> new BusinessRuleException("invalid-assignee", userId + " is not a member"));
        if (assignee.role() == Role.GUEST) {
            throw new BusinessRuleException("invalid-assignee", "Guests cannot be assigned tasks");
        }
    }

    private void notifyAssignee(Task task, Member actor, Instant now) {
        if (task.assigneeId() != null && !task.assigneeId().equals(actor.userId())) {
            notifications.insert(new Notification(UUID.randomUUID(), task.assigneeId(), Notification.Kind.ASSIGNED,
                task.id(), actor.userId(), now));
        }
    }

    private boolean guestOn(Member member, Project project) {
        return member.role() == Role.GUEST && projects.isGuest(project.id(), member.userId());
    }

    private boolean canSee(Member member, Project project) {
        return member.role() != Role.GUEST || projects.isGuest(project.id(), member.userId());
    }

    private static String localPart(String email) {
        int at = email.indexOf('@');
        return (at < 0 ? email : email.substring(0, at)).toLowerCase();
    }

    private static PreconditionFailedException stale(Task current) {
        return new PreconditionFailedException("version-mismatch",
            "Task changed since you read it; current version is " + current.version());
    }

    static NotFoundException taskNotFound(UUID id) {
        return new NotFoundException("task-not-found", "No task " + id);
    }
}
