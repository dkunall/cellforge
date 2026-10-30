package com.cellforge.edge.directory.adapter.web;

import com.cellforge.edge.directory.application.TenantMoves;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class InternalMoveController {

    record MoveRequest(@NotBlank String targetCell) {
    }

    private final TenantMoves moves;

    InternalMoveController(TenantMoves moves) {
        this.moves = moves;
    }

    /** Synchronous: the call returns once the tenant serves from the new cell, or after rolling back. */
    @PostMapping("/internal/tenants/{tenantId}/move")
    TenantMoves.Moved move(@PathVariable UUID tenantId, @Valid @RequestBody MoveRequest body) {
        return moves.move(tenantId, body.targetCell().trim());
    }
}
