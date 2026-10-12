package com.gymops.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/** Thread-safe JSON HTTP client for integration tests (supports PATCH and concurrent calls). */
public class Api {

    public record Resp(int status, JsonNode body, HttpResponse<String> raw) {
        public String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }

        public long id() {
            return body.get("id").asLong();
        }

        public long id(String field) {
            return body.get(field).asLong();
        }

        @Override
        public String toString() {
            return status + " " + raw.body();
        }
    }

    private final HttpClient client = HttpClient.newHttpClient();
    /** A request body sent exactly as given (e.g. a webhook whose signature covers the raw bytes). */
    public record Raw(String json) {
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;

    public Api(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    public Resp get(String path, String token) {
        return send("GET", path, token, null, Map.of());
    }

    public Resp post(String path, String token, Object body) {
        return send("POST", path, token, body, Map.of());
    }

    public Resp post(String path, String token, Object body, Map<String, String> headers) {
        return send("POST", path, token, body, headers);
    }

    public Resp patch(String path, String token, Object body) {
        return send("PATCH", path, token, body, Map.of());
    }

    public Resp send(String method, String path, String token, Object body, Map<String, String> headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body instanceof Raw raw ? raw.json()
                                    : mapper.writeValueAsString(body)));
            if (token != null) {
                b.header("Authorization", "Bearer " + token);
            }
            headers.forEach(b::header);
            HttpResponse<String> response = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode json = response.body() == null || response.body().isBlank() ? mapper.nullNode() : mapper.readTree(response.body());
            return new Resp(response.statusCode(), json, response);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
