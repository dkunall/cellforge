package com.cellforge.edge.gateway.adapter.web;

import com.cellforge.edge.gateway.application.CellForwarder;
import com.cellforge.shared.error.UpstreamUnavailableException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class HttpCellForwarder implements CellForwarder {

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2)).version(HttpClient.Version.HTTP_1_1).build();
    private final Duration timeout;

    HttpCellForwarder(@Value("${cellforge.cells.timeout:5s}") Duration timeout) {
        this.timeout = timeout;
    }

    @Override
    public Inbound forward(String cellUrl, UUID tenantId, Outbound request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(cellUrl + request.pathAndQuery()))
            .timeout(timeout)
            .method(request.method(), request.body().length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(request.body()));
        request.headers().forEach(builder::header);
        builder.header("X-Tenant-Id", tenantId.toString());
        try {
            HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Inbound(response.statusCode(), response.headers().map(), response.body());
        } catch (HttpTimeoutException e) {
            throw new UpstreamUnavailableException("cell-timeout", "The workspace's cell did not answer in time");
        } catch (IOException e) {
            throw new UpstreamUnavailableException("cell-unavailable", "The workspace's cell is unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamUnavailableException("cell-unavailable", "Interrupted calling the cell");
        }
    }
}
