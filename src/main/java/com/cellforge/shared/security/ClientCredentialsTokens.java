package com.cellforge.shared.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * OAuth2 client-credentials against the identity provider, cached until shortly before the token expires.
 */
public class ClientCredentialsTokens implements ServiceTokens {

    private record Cached(String token, Instant refreshAt) {
    }

    private static final Duration EARLY = Duration.ofSeconds(30);

    private final URI tokenUri;
    private final String form;
    private final HttpClient http;
    private final ObjectMapper json;
    private final Clock clock;
    private volatile Cached cached;

    public ClientCredentialsTokens(URI tokenUri, String clientId, String clientSecret, String scope, ObjectMapper json,
                                   Clock clock) {
        this.tokenUri = tokenUri;
        this.form = "grant_type=client_credentials&client_id=" + encode(clientId) + "&client_secret="
            + encode(clientSecret) + "&scope=" + encode(scope);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        this.json = json;
        this.clock = clock;
    }

    @Override
    public String token() {
        Cached current = cached;
        if (current != null && clock.instant().isBefore(current.refreshAt())) {
            return current.token();
        }
        synchronized (this) {
            if (cached == null || !clock.instant().isBefore(cached.refreshAt())) {
                cached = fetch();
            }
            return cached.token();
        }
    }

    private Cached fetch() {
        HttpRequest request = HttpRequest.newBuilder(tokenUri).timeout(Duration.ofSeconds(3))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Token endpoint answered " + response.statusCode());
            }
            JsonNode body = json.readTree(response.body());
            long expiresIn = body.path("expires_in").asLong(300);
            return new Cached(body.get("access_token").asText(),
                clock.instant().plusSeconds(expiresIn).minus(EARLY));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted fetching a service token", e);
        } catch (IOException e) {
            throw new IllegalStateException("Could not reach the token endpoint", e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
