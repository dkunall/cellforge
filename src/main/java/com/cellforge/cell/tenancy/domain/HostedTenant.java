package com.cellforge.cell.tenancy.domain;

import java.time.Instant;
import java.util.UUID;

public record HostedTenant(UUID id, String slug, String name, TenantStatus status, Instant createdAt) {
}
