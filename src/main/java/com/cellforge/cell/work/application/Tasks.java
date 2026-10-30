package com.cellforge.cell.work.application;

import com.cellforge.cell.work.domain.Task;
import com.cellforge.cell.work.domain.TaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface Tasks {

    void insert(Task task);

    Optional<Task> find(UUID id);

    List<Task> board(UUID projectId);

    List<Task> column(UUID projectId, TaskStatus status);

    /** The first rank in the column after {@code rank}, or empty if {@code rank} is last. Null means "before all". */
    Optional<String> rankAfter(UUID projectId, TaskStatus status, String rank);

    Optional<String> lastRank(UUID projectId, TaskStatus status);

    boolean update(Task task, int expectedVersion);

    void move(UUID id, TaskStatus status, String rank, Instant at);

    /** Respreading a column is not an edit, so it leaves task versions alone. */
    void setRank(UUID id, String rank);

    void deferRankUniqueness();

    boolean delete(UUID id);

    List<Task> openAssignedTo(String userId, int limit);
}
