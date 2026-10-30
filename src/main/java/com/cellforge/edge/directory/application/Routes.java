package com.cellforge.edge.directory.application;

import com.cellforge.edge.directory.domain.TenantRoute;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Directory lookups on the request path, cached briefly. A stale entry after a move is harmless: the old cell
 * answers 421 and the gateway evicts and asks again.
 */
@Component
public class Routes {

    private final Directory directory;
    private final Cache<String, TenantRoute> cache = Caffeine.newBuilder()
        .maximumSize(100_000).expireAfterWrite(Duration.ofSeconds(30)).build();

    public Routes(Directory directory) {
        this.directory = directory;
    }

    public Optional<TenantRoute> resolve(String slug) {
        TenantRoute cached = cache.getIfPresent(slug);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<TenantRoute> found = directory.bySlug(slug);
        found.ifPresent(route -> cache.put(slug, route));
        return found;
    }

    public void evict(String slug) {
        cache.invalidate(slug);
    }
}
