package com.cellforge.edge.directory.adapter.cell;

import com.cellforge.edge.directory.application.CellClient;
import com.cellforge.shared.error.UpstreamUnavailableException;
import com.cellforge.shared.security.ServiceTokens;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
class HttpCellClient implements CellClient {

    private final RestClient http;
    private final ServiceTokens tokens;

    HttpCellClient(RestClient.Builder builder, ServiceTokens tokens, @Value("${cellforge.cells.timeout:5s}") Duration timeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(timeout);
        this.http = builder.requestFactory(factory).build();
        this.tokens = tokens;
    }

    @Override
    public void provision(String cellUrl, UUID tenantId, String slug, String name, Owner owner) {
        Map<String, Object> body = Map.of("slug", slug, "name", name, "owner",
            owner.email() == null
                ? Map.of("userId", owner.userId(), "displayName", owner.displayName())
                : Map.of("userId", owner.userId(), "email", owner.email(), "displayName", owner.displayName()));
        try {
            http.put().uri(cellUrl + "/internal/tenants/{id}", tenantId)
                .headers(h -> h.setBearerAuth(tokens.token()))
                .body(body).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw new UpstreamUnavailableException("cell-unavailable", "Could not provision in the cell; retry to resume");
        }
    }

    @Override
    public long seats(String cellUrl, UUID tenantId) {
        try {
            Map<?, ?> body = http.get().uri(cellUrl + "/internal/tenants/{id}/seats", tenantId)
                .headers(h -> h.setBearerAuth(tokens.token())).retrieve().body(Map.class);
            return ((Number) body.get("seats")).longValue();
        } catch (RestClientException | NullPointerException e) {
            throw new UpstreamUnavailableException("cell-unavailable", "Could not count seats in the cell");
        }
    }

    @Override
    public Optional<String> role(String cellUrl, UUID tenantId, String userId) {
        try {
            Map<?, ?> body = http.get().uri(cellUrl + "/internal/tenants/{id}/members/{user}", tenantId, userId)
                .headers(h -> h.setBearerAuth(tokens.token())).retrieve().body(Map.class);
            return Optional.ofNullable(body).map(b -> (String) b.get("role"));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            throw new UpstreamUnavailableException("cell-unavailable", "Could not check membership in the cell");
        }
    }

    @Override
    public void transfer(String cellUrl, UUID tenantId, TransferStep step) {
        HttpMethod method = step == TransferStep.PURGE ? HttpMethod.DELETE : HttpMethod.POST;
        String path = step == TransferStep.PURGE ? "" : "/" + step.name().toLowerCase();
        try {
            http.method(method).uri(cellUrl + "/internal/tenants/{id}/transfer" + path, tenantId)
                .headers(h -> h.setBearerAuth(tokens.token())).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw new UpstreamUnavailableException("cell-unavailable", "Transfer step " + step + " failed: " + e.getMessage());
        }
    }

    @Override
    public Snapshot export(String cellUrl, UUID tenantId) {
        try {
            return http.get().uri(cellUrl + "/internal/tenants/{id}/transfer", tenantId)
                .headers(h -> h.setBearerAuth(tokens.token())).retrieve().body(Snapshot.class);
        } catch (RestClientException e) {
            throw new UpstreamUnavailableException("cell-unavailable", "Export failed: " + e.getMessage());
        }
    }

    @Override
    public Map<String, TableDigest> restore(String cellUrl, UUID tenantId, Map<String, String> tables) {
        try {
            return http.put().uri(cellUrl + "/internal/tenants/{id}/transfer", tenantId)
                .headers(h -> h.setBearerAuth(tokens.token())).body(Map.of("tables", tables)).retrieve()
                .body(new ParameterizedTypeReference<Map<String, TableDigest>>() { });
        } catch (RestClientException e) {
            throw new UpstreamUnavailableException("cell-unavailable", "Restore failed: " + e.getMessage());
        }
    }
}
