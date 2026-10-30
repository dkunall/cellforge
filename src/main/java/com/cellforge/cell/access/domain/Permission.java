package com.cellforge.cell.access.domain;

public enum Permission {
    PROJECT_READ,
    PROJECT_CREATE,
    PROJECT_MANAGE,
    TASK_WRITE,
    TASK_DELETE_ANY,
    COMMENT,
    MEMBER_INVITE,
    MEMBER_MANAGE,
    AUDIT_READ,
    BILLING_MANAGE;

    long bit() {
        return 1L << ordinal();
    }
}
