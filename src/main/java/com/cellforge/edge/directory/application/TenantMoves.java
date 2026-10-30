package com.cellforge.edge.directory.application;

import com.cellforge.edge.directory.application.CellClient.TransferStep;
import com.cellforge.edge.directory.domain.RouteStatus;
import com.cellforge.edge.directory.domain.TenantRoute;
import com.cellforge.shared.error.InvalidRequestException;
import com.cellforge.shared.error.NotFoundException;
import com.cellforge.shared.error.StateConflictException;
import com.cellforge.shared.error.UpstreamUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Moves a tenant between cells: freeze the source, copy, compare digests, flip the directory, then retire the
 * source copy. Until the flip, any failure puts everything back as it was. After the flip the move only goes
 * forward; a failed cleanup leaves a frozen or moved copy behind, which the source answers 421 for.
 */
@Service
public class TenantMoves {

    public record Moved(UUID tenantId, String from, String to, Map<String, CellClient.TableDigest> digest,
                        long writesPausedMillis) {
    }

    private static final Logger log = LoggerFactory.getLogger(TenantMoves.class);

    private final Directory directory;
    private final CellClient cells;
    private final Routes routes;
    private final Clock clock;

    public TenantMoves(Directory directory, CellClient cells, Routes routes, Clock clock) {
        this.directory = directory;
        this.cells = cells;
        this.routes = routes;
        this.clock = clock;
    }

    public Moved move(UUID tenantId, String targetCell) {
        TenantRoute route = directory.byId(tenantId)
            .orElseThrow(() -> new NotFoundException("tenant-not-found", "No tenant " + tenantId));
        String targetUrl = directory.cellUrl(targetCell)
            .orElseThrow(() -> new InvalidRequestException("unknown-cell", "No cell called " + targetCell));
        if (route.cellId().equals(targetCell)) {
            throw new StateConflictException("already-in-cell", "Tenant already lives in " + targetCell);
        }
        if (route.status() != RouteStatus.ACTIVE || !directory.beginMove(tenantId)) {
            throw new StateConflictException("move-in-progress", "Tenant is " + route.status().name().toLowerCase());
        }

        String sourceUrl = route.cellUrl();
        // The wall clock, not the injectable one: this is a duration we report, not a business time.
        long started = System.nanoTime();
        Map<String, CellClient.TableDigest> digest;
        try {
            cells.transfer(sourceUrl, tenantId, TransferStep.FREEZE);
            CellClient.Snapshot snapshot = cells.export(sourceUrl, tenantId);
            Map<String, CellClient.TableDigest> copied = cells.restore(targetUrl, tenantId, snapshot.tables());
            if (!copied.equals(snapshot.digest())) {
                throw new IllegalStateException("Copied data does not match the source: " + copied + " vs " + snapshot.digest());
            }
            digest = snapshot.digest();
        } catch (RuntimeException e) {
            rollBack(tenantId, sourceUrl, targetUrl, e);
            throw new UpstreamUnavailableException("move-failed", "Move rolled back: " + e.getMessage());
        }

        directory.moveTo(tenantId, targetCell);
        routes.evict(route.slug());
        cells.transfer(targetUrl, tenantId, TransferStep.ACTIVATE);
        long paused = Duration.ofNanos(System.nanoTime() - started).toMillis();
        try {
            cells.transfer(sourceUrl, tenantId, TransferStep.MOVED);
            cells.transfer(sourceUrl, tenantId, TransferStep.PURGE);
        } catch (RuntimeException e) {
            log.warn("Tenant {} moved to {} but the copy in {} was not cleaned up: {}", tenantId, targetCell,
                route.cellId(), e.getMessage());
        }
        log.info("Moved tenant {} from {} to {} at {}; writes paused {} ms", tenantId, route.cellId(), targetCell,
            Instant.now(clock), paused);
        return new Moved(tenantId, route.cellId(), targetCell, digest, paused);
    }

    private void rollBack(UUID tenantId, String sourceUrl, String targetUrl, RuntimeException cause) {
        log.warn("Moving tenant {} failed, rolling back: {}", tenantId, cause.getMessage());
        try {
            cells.transfer(targetUrl, tenantId, TransferStep.PURGE);
        } catch (RuntimeException e) {
            log.warn("Could not remove the partial copy of {}: {}", tenantId, e.getMessage());
        }
        try {
            cells.transfer(sourceUrl, tenantId, TransferStep.UNFREEZE);
        } finally {
            directory.setStatus(tenantId, RouteStatus.ACTIVE);
        }
    }
}
