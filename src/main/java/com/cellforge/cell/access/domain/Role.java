package com.cellforge.cell.access.domain;

import static com.cellforge.cell.access.domain.Permission.AUDIT_READ;
import static com.cellforge.cell.access.domain.Permission.BILLING_MANAGE;
import static com.cellforge.cell.access.domain.Permission.COMMENT;
import static com.cellforge.cell.access.domain.Permission.MEMBER_INVITE;
import static com.cellforge.cell.access.domain.Permission.MEMBER_MANAGE;
import static com.cellforge.cell.access.domain.Permission.PROJECT_CREATE;
import static com.cellforge.cell.access.domain.Permission.PROJECT_MANAGE;
import static com.cellforge.cell.access.domain.Permission.PROJECT_READ;
import static com.cellforge.cell.access.domain.Permission.TASK_DELETE_ANY;
import static com.cellforge.cell.access.domain.Permission.TASK_WRITE;

import java.util.Arrays;

/**
 * Each role is a bitset of permissions, so a check is one AND. Ranks order roles for "who may change whom":
 * nobody can grant or remove a role above their own.
 */
public enum Role {
    OWNER(3, Permission.values()),
    ADMIN(2, PROJECT_READ, PROJECT_CREATE, PROJECT_MANAGE, TASK_WRITE, TASK_DELETE_ANY, COMMENT, MEMBER_INVITE,
        MEMBER_MANAGE, AUDIT_READ),
    MEMBER(1, PROJECT_READ, PROJECT_CREATE, TASK_WRITE, COMMENT),
    GUEST(0, PROJECT_READ, COMMENT);

    private final int rank;
    private final long mask;

    Role(int rank, Permission... permissions) {
        this.rank = rank;
        this.mask = Arrays.stream(permissions).mapToLong(Permission::bit).reduce(0L, (a, b) -> a | b);
    }

    public boolean allows(Permission permission) {
        return (mask & permission.bit()) != 0;
    }

    public boolean outranks(Role other) {
        return rank > other.rank;
    }

    public boolean atLeast(Role other) {
        return rank >= other.rank;
    }

    /** Guests don't take a paid seat. */
    public boolean takesSeat() {
        return this != GUEST;
    }
}
