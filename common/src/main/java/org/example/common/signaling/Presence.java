package org.example.common.signaling;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class Presence {
    private Presence() {
    }

    @SuppressWarnings("BusyWait")
    public static void startHeartbeat(String baseUrl, String agentId, int periodSeconds) {
        Thread t = new Thread(() -> {
            HttpClient client = HttpClient.newHttpClient();
            URI uri = URI.create(baseUrl + "/touch/agents/" + agentId + "/ready");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(uri)
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build();
                    client.send(req, HttpResponse.BodyHandlers.discarding());
                } catch (Exception ignored) {
                }
                try {
                    Thread.sleep(Math.max(1, periodSeconds) * 1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "presence-heartbeat");
        t.setDaemon(true);
        t.start();
    }

    public static List<String> listAgents(String baseUrl, int maxAgeSec) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            URI uri = URI.create(baseUrl + "/agents?maxAgeSec=" + maxAgeSec);
            HttpRequest req = HttpRequest.newBuilder().uri(uri).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                String body = resp.body();
                body = body.trim();
                if (body.startsWith("[")) {
                    body = body.substring(1, body.length() - 1);
                    String[] arr = body.split(",");
                    List<String> list = new CopyOnWriteArrayList<>();
                    for (String s : arr) {
                        s = s.trim().replaceAll("^\"|\"$", "");
                        if (!s.isEmpty()) list.add(s);
                    }
                    return list;
                }
            }
        } catch (Exception ignored) {
        }
        return List.of();
    }
}
