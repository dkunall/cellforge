package com.cellforge.cell.access.domain;

import com.cellforge.shared.error.BusinessRuleException;
import com.cellforge.shared.error.ForbiddenException;

/**
 * Who may change whose role. Kept apart from storage so the whole table of cases is unit tested.
 */
public final class MembershipRules {

    private MembershipRules() {
    }

    public static void checkInvite(Member actor, Role role) {
        if (!actor.can(Permission.MEMBER_INVITE)) {
            throw new ForbiddenException("permission-denied", "Your role cannot invite people");
        }
        if (role == Role.OWNER) {
            throw new BusinessRuleException("owner-by-invite", "Invite as admin, then transfer ownership");
        }
        if (!actor.role().atLeast(role)) {
            throw new ForbiddenException("role-too-high", "You cannot invite someone above your own role");
        }
    }

    /**
     * @param owners how many owners the workspace has now, so the last one cannot step down
     */
    public static void checkRoleChange(Member actor, Member target, Role newRole, long owners) {
        if (!actor.can(Permission.MEMBER_MANAGE)) {
            throw new ForbiddenException("permission-denied", "Your role cannot change roles");
        }
        boolean self = actor.userId().equals(target.userId());
        if (!self && actor.role() != Role.OWNER && !actor.role().outranks(target.role())) {
            throw new ForbiddenException("role-too-high", "You can only change people below your own role");
        }
        if (!actor.role().atLeast(newRole)) {
            throw new ForbiddenException("role-too-high", "You cannot grant a role above your own");
        }
        if (target.role() == Role.OWNER && newRole != Role.OWNER && owners <= 1) {
            throw new BusinessRuleException("last-owner", "A workspace needs at least one owner");
        }
    }

    public static void checkRemoval(Member actor, Member target, long owners) {
        boolean self = actor.userId().equals(target.userId());
        if (!self) {
            if (!actor.can(Permission.MEMBER_MANAGE)) {
                throw new ForbiddenException("permission-denied", "Your role cannot remove people");
            }
            if (actor.role() != Role.OWNER && !actor.role().outranks(target.role())) {
                throw new ForbiddenException("role-too-high", "You can only remove people below your own role");
            }
        }
        if (target.role() == Role.OWNER && owners <= 1) {
            throw new BusinessRuleException("last-owner", "A workspace needs at least one owner");
        }
    }
}
