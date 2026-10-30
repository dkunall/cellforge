package com.cellforge.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** Tracks real time plus an offset tests can push forward, so billing periods can end in milliseconds. */
public final class MutableClock extends Clock {

    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

    public void advance(Duration by) {
        offset.accumulateAndGet(by, Duration::plus);
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset.get());
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return ZoneOffset.UTC.equals(zone) ? this : Clock.fixed(instant(), zone);
    }
}
