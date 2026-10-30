package com.cellforge.edge.directory.domain;

import java.time.Instant;
import java.util.UUID;

/** Where a tenant lives. The edge resolves every request's tenant slug to one of these. */
public record TenantRoute(UUID id, String slug, String name, String cellId, String cellUrl, RouteStatus status,
                          String ownerUserId, Instant createdAt) {
}
