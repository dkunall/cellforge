package com.cellforge.cell.tenancy.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * Caps how many requests one tenant can have running in this cell at once, so a tenant hammering expensive
 * queries holds a bounded share of the connection pool and the rest keep theirs.
 */
@Component
public class TenantBulkhead {

    private record Slots(int limit, Semaphore semaphore) {
    }

    /**
     * Released into the semaphore it was taken from, even if a plan change has replaced that semaphore since;
     * otherwise the new one would gain a permit that nobody took.
     */
    public static final class Permit implements AutoCloseable {
        private final Semaphore semaphore;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                semaphore.release();
            }
        }
    }

    private final Cache<UUID, Slots> slots = Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(10)).build();

    /** Empty when the tenant stays at its limit for longer than {@code wait}. */
    public Optional<Permit> tryEnter(UUID tenantId, int limit, Duration wait) throws InterruptedException {
        Slots current = slots.asMap().compute(tenantId, (id, existing) ->
            existing != null && existing.limit() == limit ? existing : new Slots(limit, new Semaphore(limit)));
        return current.semaphore().tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS)
            ? Optional.of(new Permit(current.semaphore()))
            : Optional.empty();
    }
}
