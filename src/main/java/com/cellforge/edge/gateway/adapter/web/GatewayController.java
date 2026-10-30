package com.cellforge.edge.gateway.adapter.web;

import com.cellforge.edge.gateway.application.CellForwarder;
import com.cellforge.edge.gateway.application.Gateway;
import com.cellforge.shared.error.InvalidRequestException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Everything under /v1 that the edge does not answer itself goes to a cell. More specific mappings
 * (/v1/tenants, /v1/billing) win over this catch-all.
 */
@RestController
class GatewayController {

    static final String TENANT_HEADER = "X-Tenant";
    private static final int MAX_BODY = 1 << 20;
    private static final List<String> FORWARDED = List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE,
        HttpHeaders.ACCEPT, HttpHeaders.IF_MATCH, HttpHeaders.IF_NONE_MATCH, "Idempotency-Key");
    private static final Set<String> RETURNED = Set.of("content-type", "etag", "retry-after", "location",
        "idempotent-replayed");

    private final Gateway gateway;

    GatewayController(Gateway gateway) {
        this.gateway = gateway;
    }

    @RequestMapping("/v1/**")
    ResponseEntity<byte[]> proxy(HttpServletRequest request) throws IOException {
        String slug = request.getHeader(TENANT_HEADER);
        if (slug == null || slug.isBlank()) {
            throw new InvalidRequestException("tenant-required", "Missing " + TENANT_HEADER + " header");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : FORWARDED) {
            String value = request.getHeader(name);
            if (value != null) {
                headers.put(name, value);
            }
        }
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        CellForwarder.Inbound response = gateway.handle(slug.trim(),
            new CellForwarder.Outbound(request.getMethod(), path, headers, body(request)));

        HttpHeaders out = new HttpHeaders();
        response.headers().forEach((name, values) -> {
            if (RETURNED.contains(name.toLowerCase())) {
                out.put(name, values);
            }
        });
        return ResponseEntity.status(response.status()).headers(out).body(response.body());
    }

    private static byte[] body(HttpServletRequest request) throws IOException {
        try (InputStream in = request.getInputStream()) {
            byte[] bytes = in.readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) {
                throw new InvalidRequestException("body-too-large", "Request bodies are limited to 1 MiB");
            }
            return bytes;
        }
    }
}
