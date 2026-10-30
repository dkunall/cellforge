package com.cellforge.cell.work.application;

import com.cellforge.cell.access.application.Members;
import com.cellforge.cell.access.domain.Member;
import com.cellforge.cell.access.domain.Permission;
import com.cellforge.cell.access.domain.Role;
import com.cellforge.cell.tenancy.application.EntitlementSource;
import com.cellforge.cell.tenancy.application.TenantContext;
import com.cellforge.cell.work.domain.Activity;
import com.cellforge.cell.work.domain.Project;
import com.cellforge.cell.work.domain.ProjectAccess;
import com.cellforge.cell.work.domain.Task;
import com.cellforge.shared.error.BusinessRuleException;
import com.cellforge.shared.error.NotFoundException;
import com.cellforge.shared.error.StateConflictException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {

    public record Board(Project project, List<Task> tasks) {
    }

    private final Projects projects;
    private final Tasks tasks;
    private final Members members;
    private final ActivityLog activity;
    private final EntitlementSource entitlements;
    private final Clock clock;

    public ProjectService(Projects projects, Tasks tasks, Members members, ActivityLog activity,
                          EntitlementSource entitlements, Clock clock) {
        this.projects = projects;
        this.tasks = tasks;
        this.members = members;
        this.activity = activity;
        this.entitlements = entitlements;
        this.clock = clock;
    }

    @Transactional
    public Project create(String key, String name) {
        Member actor = TenantContext.require(Permission.PROJECT_CREATE);
        int limit = entitlements.forTenant(actor.tenantId()).maxProjects();
        projects.lockQuota();
        if (projects.countActive() >= limit) {
            throw new BusinessRuleException("project-limit", "This plan allows " + limit + " active projects");
        }
        Project project = new Project(UUID.randomUUID(), actor.tenantId(), key, name.trim(), actor.userId(),
            clock.instant(), null, 0);
        try {
            projects.insert(project);
        } catch (DuplicateKeyException e) {
            throw new StateConflictException("project-key-taken", "Project key " + key + " is in use");
        }
        activity.record(project.id(), null, actor.userId(), "project.created", Map.of("key", key));
        return project;
    }

    @Transactional(readOnly = true)
    public List<Project> list() {
        Member member = TenantContext.member();
        return member.role() == Role.GUEST ? projects.openToGuest(member.userId()) : projects.all();
    }

    @Transactional(readOnly = true)
    public Project get(UUID id) {
        return readable(id);
    }

    @Transactional(readOnly = true)
    public Board board(UUID id) {
        Project project = readable(id);
        return new Board(project, tasks.board(id));
    }

    @Transactional(readOnly = true)
    public List<Activity> activity(UUID id, int limit) {
        readable(id);
        return activity.latest(id, Math.min(Math.max(limit, 1), 200));
    }

    @Transactional
    public Project archive(UUID id) {
        Member actor = TenantContext.member();
        Project project = projects.lockForChange(id).orElseThrow(() -> notFound(id));
        ProjectAccess.checkWrite(actor, project, false, Permission.PROJECT_MANAGE);
        projects.archive(id, clock.instant());
        activity.record(id, null, actor.userId(), "project.archived", Map.of());
        return projects.find(id).orElseThrow();
    }

    @Transactional
    public void openToGuest(UUID id, String userId) {
        Member actor = TenantContext.member();
        Project project = projects.lockForChange(id).orElseThrow(() -> notFound(id));
        ProjectAccess.checkWrite(actor, project, false, Permission.PROJECT_MANAGE);
        Member guest = members.find(userId)
            .orElseThrow(() -> new NotFoundException("member-not-found", "No member " + userId));
        if (guest.role() != Role.GUEST) {
            throw new BusinessRuleException("not-a-guest", userId + " is a " + guest.role() + " and already sees every project");
        }
        projects.addGuest(id, userId);
        activity.record(id, null, actor.userId(), "guest.added", Map.of("userId", userId));
    }

    @Transactional
    public void closeToGuest(UUID id, String userId) {
        Member actor = TenantContext.member();
        Project project = projects.lockForChange(id).orElseThrow(() -> notFound(id));
        ProjectAccess.checkWrite(actor, project, false, Permission.PROJECT_MANAGE);
        if (projects.removeGuest(id, userId)) {
            activity.record(id, null, actor.userId(), "guest.removed", Map.of("userId", userId));
        }
    }

    private Project readable(UUID id) {
        Member member = TenantContext.member();
        Project project = projects.find(id).orElseThrow(() -> notFound(id));
        ProjectAccess.checkRead(member, project, member.role() == Role.GUEST && projects.isGuest(id, member.userId()));
        return project;
    }

    static NotFoundException notFound(UUID id) {
        return new NotFoundException("project-not-found", "No project " + id);
    }
}
