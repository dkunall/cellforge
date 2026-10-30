package com.cellforge.cell.access.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cellforge.shared.error.BusinessRuleException;
import com.cellforge.shared.error.ForbiddenException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MembershipRulesTest {

    private static final UUID TENANT = UUID.randomUUID();

    private static Member as(String id, Role role) {
        return new Member(TENANT, id, id + "@example.test", id, role, Instant.EPOCH);
    }

    @Test
    void adminsInviteUpToTheirOwnRole() {
        Member admin = as("ada", Role.ADMIN);
        assertThatCode(() -> MembershipRules.checkInvite(admin, Role.ADMIN)).doesNotThrowAnyException();
        assertThatCode(() -> MembershipRules.checkInvite(admin, Role.GUEST)).doesNotThrowAnyException();
    }

    @Test
    void nobodyInvitesAnOwner() {
        assertThatThrownBy(() -> MembershipRules.checkInvite(as("o", Role.OWNER), Role.OWNER))
            .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void membersCannotInvite() {
        assertThatThrownBy(() -> MembershipRules.checkInvite(as("m", Role.MEMBER), Role.GUEST))
            .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void adminCannotTouchAnotherAdminOrPromoteToOwner() {
        Member admin = as("ada", Role.ADMIN);
        assertThatThrownBy(() -> MembershipRules.checkRoleChange(admin, as("bob", Role.ADMIN), Role.MEMBER, 1))
            .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> MembershipRules.checkRoleChange(admin, as("cy", Role.MEMBER), Role.OWNER, 1))
            .isInstanceOf(ForbiddenException.class);
        assertThatCode(() -> MembershipRules.checkRoleChange(admin, as("cy", Role.MEMBER), Role.ADMIN, 1))
            .doesNotThrowAnyException();
    }

    @Test
    void lastOwnerCannotStepDownOrLeave() {
        Member owner = as("o", Role.OWNER);
        assertThatThrownBy(() -> MembershipRules.checkRoleChange(owner, owner, Role.ADMIN, 1))
            .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> MembershipRules.checkRemoval(owner, owner, 1))
            .isInstanceOf(BusinessRuleException.class);
        assertThatCode(() -> MembershipRules.checkRoleChange(owner, owner, Role.ADMIN, 2)).doesNotThrowAnyException();
    }

    @Test
    void anyoneMayLeaveButOnlyManagersRemoveOthers() {
        Member member = as("m", Role.MEMBER);
        assertThatCode(() -> MembershipRules.checkRemoval(member, member, 1)).doesNotThrowAnyException();
        assertThatThrownBy(() -> MembershipRules.checkRemoval(member, as("g", Role.GUEST), 1))
            .isInstanceOf(ForbiddenException.class);
        assertThatCode(() -> MembershipRules.checkRemoval(as("o", Role.OWNER), as("a", Role.ADMIN), 1))
            .doesNotThrowAnyException();
    }
}
