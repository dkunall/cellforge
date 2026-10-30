package com.cellforge.cell.work.domain;

import com.cellforge.cell.access.domain.Member;
import com.cellforge.cell.access.domain.Permission;
import com.cellforge.cell.access.domain.Role;
import com.cellforge.shared.error.ForbiddenException;
import com.cellforge.shared.error.NotFoundException;
import com.cellforge.shared.error.StateConflictException;

/**
 * Project-level checks layered on top of role permissions: guests only see projects opened to them, and an
 * archived project is read-only for everyone.
 */
public final class ProjectAccess {

    private ProjectAccess() {
    }

    /** A guest asking about a project they cannot see gets the same 404 as for one that does not exist. */
    public static void checkRead(Member member, Project project, boolean guestOnProject) {
        if (member.role() == Role.GUEST && !guestOnProject) {
            throw new NotFoundException("project-not-found", "No project " + project.id());
        }
    }

    public static void checkWrite(Member member, Project project, boolean guestOnProject, Permission needed) {
        checkRead(member, project, guestOnProject);
        if (!member.can(needed)) {
            throw new ForbiddenException("permission-denied", "Your role (" + member.role() + ") cannot " + needed);
        }
        if (project.archived()) {
            throw new StateConflictException("project-archived", "Project " + project.key() + " is archived");
        }
    }

    public static void checkDeleteTask(Member member, Task task) {
        if (!member.can(Permission.TASK_DELETE_ANY) && !member.userId().equals(task.createdBy())) {
            throw new ForbiddenException("permission-denied", "Only the creator or an admin can delete a task");
        }
    }
}
