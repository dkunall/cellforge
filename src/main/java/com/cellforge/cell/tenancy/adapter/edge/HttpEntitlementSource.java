package com.cellforge.cell.tenancy.adapter.edge;

import com.cellforge.cell.tenancy.application.EntitlementSource;
import com.cellforge.cell.tenancy.domain.Entitlements;
import com.cellforge.shared.error.UnavailableException;
import com.cellforge.shared.security.ServiceTokens;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Plans live at the edge; cells cache what they need. The TTL bounds how long a downgrade takes to apply if
 * the eviction message is lost; normally the edge's plan-change event evicts the entry within a second.
 */
@Component
class HttpEntitlementSource implements EntitlementSource {

    record EntitlementsView(String plan, Set<String> features, int maxSeats, int maxProjects, long statementTimeoutMs,
                            int maxConcurrentRequests) {
    }

    private final RestClient http;
    private final ServiceTokens tokens;
    private final Cache<UUID, Entitlements> cache;

    HttpEntitlementSource(RestClient.Builder builder, ServiceTokens tokens,
                          @Value("${cellforge.edge.url}") String edgeUrl,
                          @Value("${cellforge.edge.timeout}") Duration timeout,
                          @Value("${cellforge.entitlements.cache-ttl}") Duration ttl) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(timeout);
        this.http = builder.baseUrl(edgeUrl).requestFactory(factory).build();
        this.tokens = tokens;
        this.cache = Caffeine.newBuilder().maximumSize(50_000).expireAfterWrite(ttl).build();
    }

    @Override
    public Entitlements forTenant(UUID tenantId) {
        return cache.get(tenantId, this::fetch);
    }

    @Override
    public void evict(UUID tenantId) {
        cache.invalidate(tenantId);
    }

    private Entitlements fetch(UUID tenantId) {
        try {
            EntitlementsView view = http.get().uri("/internal/entitlements/{id}", tenantId)
                .headers(h -> h.setBearerAuth(tokens.token()))
                .retrieve().body(EntitlementsView.class);
            return new Entitlements(view.plan(), Set.copyOf(view.features()), view.maxSeats(), view.maxProjects(),
                Duration.ofMillis(view.statementTimeoutMs()), view.maxConcurrentRequests());
        } catch (RestClientException e) {
            // Without the plan we cannot size the bulkhead or the query budget, so refuse rather than guess.
            throw new UnavailableException("entitlements-unavailable", "Could not load this workspace's plan", 2);
        }
    }
}
