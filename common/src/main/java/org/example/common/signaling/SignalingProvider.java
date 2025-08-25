package org.example.common.signaling;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class SignalingProvider {

    private final String baseUrl;
    private final HttpClient client = HttpClient.newHttpClient();

    public SignalingProvider(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public void putText(String key, String content) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/put/" + key))
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(content, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new RuntimeException("putText failed: " + resp.statusCode());
            }
        } catch (Exception e) {
            throw new RuntimeException("putText error", e);
        }
    }

    public void delete(String key) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/delete/" + key))
                    .timeout(Duration.ofSeconds(10))
                    .DELETE()
                    .build();
            client.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("BusyWait")
    public String waitAndGet(String key, Duration timeout, Duration interval) throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/get/" + key))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    return resp.body();
                }
            } catch (Exception ignored) {
            }
            Thread.sleep(interval.toMillis());
        }
        throw new RuntimeException("Timeout waiting for " + key);
    }
}
