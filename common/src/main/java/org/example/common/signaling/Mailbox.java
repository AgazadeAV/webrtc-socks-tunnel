package org.example.common.signaling;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class Mailbox {
    private Mailbox() {}

    public record OfferRef(String sessionId, String key) {}

    @SuppressWarnings("BusyWait")
    public static OfferRef waitNextOffer(String baseUrl, String agentId, Duration timeout, Duration poll) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        long sleep = Math.max(50L, poll.toMillis());
        String prefix = "sessions/" + agentId + "/";

        HttpClient client = HttpClient.newHttpClient();

        while (System.currentTimeMillis() < deadline) {
            try {
                URI uri = URI.create(baseUrl + "/list?prefix=" + prefix);
                HttpRequest req = HttpRequest.newBuilder().uri(uri).GET().build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() == 200) {
                    String body = resp.body().trim();
                    if (body.startsWith("[")) {
                        // убираем [ ] и парсим массив строк
                        body = body.substring(1, body.length() - 1).trim();
                        if (!body.isEmpty()) {
                            String[] arr = body.split(",");
                            for (String s : arr) {
                                s = s.trim().replaceAll("^\"|\"$", "");
                                if (s.endsWith("/offer.sdp")) {
                                    String[] parts = s.split("/");
                                    if (parts.length >= 4) {
                                        return new OfferRef(parts[2], s);
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            Thread.sleep(sleep);
        }
        return null;
    }
}
