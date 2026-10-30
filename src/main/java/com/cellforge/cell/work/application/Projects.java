package com.cellforge.cell.work.application;

import com.cellforge.cell.work.domain.Project;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface Projects {

    void insert(Project project);

    Optional<Project> find(UUID id);

    /**
     * Bumps the board version and returns the project, holding its row lock until commit. Every write to a
     * project's tasks goes through here first, so writes to one board are serialised.
     */
    Optional<Project> lockForChange(UUID id);

    int takeTaskNumber(UUID id);

    List<Project> all();

    List<Project> openToGuest(String userId);

    long countActive();

    /** Serialises project creation per tenant so the plan's project limit holds under concurrency. */
    void lockQuota();

    void archive(UUID id, Instant at);

    boolean isGuest(UUID projectId, String userId);

    void addGuest(UUID projectId, String userId);

    boolean removeGuest(UUID projectId, String userId);
}
