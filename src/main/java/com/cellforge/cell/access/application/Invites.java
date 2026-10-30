package com.cellforge.cell.access.application;

import com.cellforge.cell.access.domain.Invite;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface Invites {

    void insert(Invite invite, String tokenHash);

    Optional<Invite> findByTokenHash(String tokenHash);

    /** Marks the invite used, unless someone got there first. Returns whether this call won. */
    boolean markAccepted(UUID id, String userId, Instant at);

    boolean revoke(UUID id, Instant at);

    List<Invite> open(Instant now);
}
