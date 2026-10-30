package com.cellforge.edge.gateway.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Sends one already-admitted request to the tenant's cell. */
public interface CellForwarder {

    record Outbound(String method, String pathAndQuery, Map<String, String> headers, byte[] body) {
    }

    record Inbound(int status, Map<String, List<String>> headers, byte[] body) {
    }

    Inbound forward(String cellUrl, UUID tenantId, Outbound request);
}
