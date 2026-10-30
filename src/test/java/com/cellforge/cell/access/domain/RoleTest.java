package com.cellforge.cell.access.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class RoleTest {

    @Test
    void ownerHoldsEveryPermission() {
        assertThat(Arrays.stream(Permission.values()).allMatch(Role.OWNER::allows)).isTrue();
    }

    @Test
    void onlyOwnersManageBilling() {
        assertThat(Role.OWNER.allows(Permission.BILLING_MANAGE)).isTrue();
        assertThat(Role.ADMIN.allows(Permission.BILLING_MANAGE)).isFalse();
    }

    @Test
    void guestsReadAndCommentButCannotWriteTasks() {
        assertThat(Role.GUEST.allows(Permission.PROJECT_READ)).isTrue();
        assertThat(Role.GUEST.allows(Permission.COMMENT)).isTrue();
        assertThat(Role.GUEST.allows(Permission.TASK_WRITE)).isFalse();
        assertThat(Role.GUEST.allows(Permission.PROJECT_CREATE)).isFalse();
    }

    @Test
    void eachRoleIsASubsetOfTheOneAbove() {
        Role[] ladder = {Role.GUEST, Role.MEMBER, Role.ADMIN, Role.OWNER};
        for (int i = 1; i < ladder.length; i++) {
            Role lower = ladder[i - 1];
            Role higher = ladder[i];
            assertThat(Arrays.stream(Permission.values()).filter(lower::allows).allMatch(higher::allows))
                .as(lower + " within " + higher).isTrue();
            assertThat(higher.outranks(lower)).isTrue();
        }
    }

    @Test
    void guestsDoNotTakeSeats() {
        assertThat(Role.GUEST.takesSeat()).isFalse();
        assertThat(Role.MEMBER.takesSeat()).isTrue();
    }
}
