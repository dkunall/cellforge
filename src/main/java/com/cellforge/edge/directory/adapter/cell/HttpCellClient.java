package com.cellforge.edge.directory.adapter.cell;

import com.cellforge.edge.directory.application.CellClient;
import com.cellforge.shared.error.UpstreamUnavailableException;
import com.cellforge.shared.security.ServiceTokens;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
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
}
