package com.cellforge.edge.metering.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.cellforge.edge.metering.domain.UsageEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MeteringTest {

    private static final class MemoryLedger implements UsageLedger {
        final Set<UUID> seen = new HashSet<>();
        final Map<Instant, long[]> hours = new HashMap<>();

        @Override
        public boolean recordOnce(UsageEvent event) {
            return seen.add(event.eventId());
        }

        @Override
        public void addToHour(UUID tenantId, Instant hour, long calls, long errors) {
            long[] row = hours.computeIfAbsent(hour, h -> new long[2]);
            row[0] += calls;
            row[1] += errors;
        }

        @Override
        public long callsBetween(UUID tenantId, Instant from, Instant to) {
            return hours.entrySet().stream().filter(e -> !e.getKey().isBefore(from) && e.getKey().isBefore(to))
                .mapToLong(e -> e.getValue()[0]).sum();
        }

        @Override
        public int forgetEventsBefore(Instant cutoff) {
            return 0;
        }
    }

    private final MemoryLedger ledger = new MemoryLedger();
    private final Metering metering = new Metering(ledger, Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"), ZoneOffset.UTC));
    private final UUID tenant = UUID.randomUUID();

    @Test
    void aRedeliveredEventIsCountedOnce() {
        UsageEvent event = new UsageEvent(UUID.randomUUID(), tenant, Instant.parse("2026-03-01T10:15:00Z"), "GET", 200);
        metering.record(event);
        metering.record(event);

        assertThat(ledger.hours.get(Instant.parse("2026-03-01T10:00:00Z"))).containsExactly(1, 0);
    }

    @Test
    void eventsLandInTheirOwnHourAndServerErrorsAreTracked() {
        metering.record(new UsageEvent(UUID.randomUUID(), tenant, Instant.parse("2026-03-01T10:59:59Z"), "GET", 503));
        metering.record(new UsageEvent(UUID.randomUUID(), tenant, Instant.parse("2026-03-01T11:00:00Z"), "POST", 422));

        assertThat(ledger.hours.get(Instant.parse("2026-03-01T10:00:00Z"))).containsExactly(1, 1);
        assertThat(ledger.hours.get(Instant.parse("2026-03-01T11:00:00Z"))).containsExactly(1, 0);
        assertThat(metering.calls(tenant, Instant.parse("2026-03-01T11:00:00Z"), Instant.parse("2026-03-01T12:00:00Z")))
            .isEqualTo(1);
    }
}
