package com.cellforge.cell.tenancy.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Caps how many requests one tenant can have running in this cell at once, so a tenant hammering expensive
 * queries holds a bounded share of the connection pool and the rest keep theirs.
 */
@Component
public class TenantBulkhead {

    private record Slots(int limit, Semaphore semaphore) {
    }

    private final Cache<UUID, Slots> slots = Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(10)).build();

    /** Returns false when the tenant is at its limit for longer than {@code wait}. */
    public boolean tryEnter(UUID tenantId, int limit, Duration wait) throws InterruptedException {
        Slots current = slots.asMap().compute(tenantId, (id, existing) ->
            existing != null && existing.limit() == limit ? existing : new Slots(limit, new Semaphore(limit)));
        return current.semaphore().tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void leave(UUID tenantId, int limit) {
        Slots current = slots.getIfPresent(tenantId);
        // If the plan changed mid-request the old semaphore was replaced; releasing into the new one would
        // hand out a permit that was never taken.
        if (current != null && current.limit() == limit) {
            current.semaphore().release();
        }
    }
}
