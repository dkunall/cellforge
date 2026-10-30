package com.cellforge.cell.work.adapter.web;

import com.cellforge.cell.work.domain.Project;
import com.cellforge.cell.work.domain.Task;
import com.cellforge.cell.work.domain.TaskStatus;
import java.time.Instant;
import java.util.UUID;

final class Views {

    private Views() {
    }

    record ProjectView(UUID id, String key, String name, String createdBy, Instant createdAt, Instant archivedAt) {
        static ProjectView of(Project p) {
            return new ProjectView(p.id(), p.key(), p.name(), p.createdBy(), p.createdAt(), p.archivedAt());
        }
    }

    record TaskView(UUID id, UUID projectId, int number, String title, String description,
                    TaskStatus status, String rank, String assigneeId, String createdBy, Instant createdAt,
                    Instant updatedAt, int version) {
        static TaskView of(Task t) {
            return new TaskView(t.id(), t.projectId(), t.number(), t.title(), t.description(), t.status(), t.rank(), t.assigneeId(), t.createdBy(),
                t.createdAt(), t.updatedAt(), t.version());
        }
    }
}
