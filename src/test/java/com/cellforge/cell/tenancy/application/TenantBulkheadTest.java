package com.cellforge.cell.tenancy.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantBulkheadTest {

    private final TenantBulkhead bulkhead = new TenantBulkhead();
    private final UUID noisy = UUID.randomUUID();
    private final UUID quiet = UUID.randomUUID();

    @Test
    void aTenantAtItsLimitIsTurnedAwayWhileOthersAreNot() throws Exception {
        var first = bulkhead.tryEnter(noisy, 2, Duration.ZERO);
        var second = bulkhead.tryEnter(noisy, 2, Duration.ZERO);

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(bulkhead.tryEnter(noisy, 2, Duration.ZERO)).isEmpty();
        assertThat(bulkhead.tryEnter(quiet, 2, Duration.ZERO)).isPresent();

        first.get().close();
        assertThat(bulkhead.tryEnter(noisy, 2, Duration.ZERO)).isPresent();
    }

    @Test
    void closingTwiceReleasesOnce() throws Exception {
        var permit = bulkhead.tryEnter(noisy, 1, Duration.ZERO).orElseThrow();
        permit.close();
        permit.close();

        assertThat(bulkhead.tryEnter(noisy, 1, Duration.ZERO)).isPresent();
        assertThat(bulkhead.tryEnter(noisy, 1, Duration.ZERO)).isEmpty();
    }

    @Test
    void aPermitFromBeforeAPlanChangeDoesNotInflateTheNewLimit() throws Exception {
        var old = bulkhead.tryEnter(noisy, 1, Duration.ZERO).orElseThrow();
        var underNewPlan = bulkhead.tryEnter(noisy, 2, Duration.ZERO).orElseThrow();
        bulkhead.tryEnter(noisy, 2, Duration.ZERO).orElseThrow();

        old.close();

        assertThat(bulkhead.tryEnter(noisy, 2, Duration.ZERO)).isEmpty();
        underNewPlan.close();
        assertThat(bulkhead.tryEnter(noisy, 2, Duration.ZERO)).isPresent();
    }
}
