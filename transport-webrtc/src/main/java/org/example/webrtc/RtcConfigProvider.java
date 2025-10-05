package org.example.webrtc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCIceTransportPolicy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class RtcConfigProvider {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final int SKEW_SECONDS = 300;
    private static final int MIN_REFRESH_SECONDS = 60;

    private final URI endpoint;
    private final RTCIceTransportPolicy policy;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    private final ObjectMapper om = new ObjectMapper();

    private volatile RTCConfiguration current;
    private volatile long expiresAtEpochSeconds = 0;

    private ScheduledExecutorService ses;
    private Consumer<String> logger = s -> {};

    public RtcConfigProvider(String endpoint, RTCIceTransportPolicy policy) {
        this.endpoint = URI.create(endpoint);
        this.policy = policy;
    }

    public RTCConfiguration get() {
        ensureFreshNow();
        return current;
    }

    public synchronized void startAutoRefresh(BiConsumer<RTCConfiguration, Integer> onRefresh, Consumer<String> log) {
        this.logger = (log != null) ? log : this.logger;

        if (ses != null) return;

        ensureFreshNow();

        final BiConsumer<RTCConfiguration, Integer> refreshCb = onRefresh;

        ses = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ice-refresh");
            t.setDaemon(true);
            return t;
        });

        Runnable task = new Runnable() {
            @Override public void run() {
                try {
                    ensureFreshNow();
                    if (current != null && refreshCb != null) {
                        int ttl = (int) Math.max(0, expiresAtEpochSeconds - Instant.now().getEpochSecond());
                        refreshCb.accept(current, ttl);
                    }
                } catch (Throwable e) {
                    logger.accept("refresh error: " + e.getMessage());
                } finally {
                    long delay = Math.max(secondsUntilRefresh(), MIN_REFRESH_SECONDS);
                    try { ses.schedule(this, delay, TimeUnit.SECONDS); } catch (Throwable ignored) {}
                }
            }
        };

        ses.schedule(task, Math.max(secondsUntilRefresh(), 1), TimeUnit.SECONDS);
        logger.accept("ICE auto-refresh scheduled");
    }

    public synchronized void stopAutoRefresh() {
        if (ses != null) {
            ses.shutdownNow();
            ses = null;
        }
    }

    private void ensureFreshNow() {
        long now = Instant.now().getEpochSecond();
        if (current == null || now + SKEW_SECONDS >= expiresAtEpochSeconds) {
            fetchAndApply();
        }
    }

    private long secondsUntilRefresh() {
        long now = Instant.now().getEpochSecond();
        long refreshAt = Math.max(expiresAtEpochSeconds - SKEW_SECONDS, now + MIN_REFRESH_SECONDS);
        return Math.max(refreshAt - now, MIN_REFRESH_SECONDS);
    }

    private void fetchAndApply() {
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(endpoint).timeout(HTTP_TIMEOUT).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) throw new RuntimeException("HTTP " + resp.statusCode());

            JsonNode root = om.readTree(resp.body());
            RTCConfiguration cfg = new RTCConfiguration();
            cfg.iceTransportPolicy = policy;

            JsonNode arr = root.path("iceServers");
            if (!arr.isArray() || arr.isEmpty()) throw new RuntimeException("iceServers empty");

            for (Iterator<JsonNode> it = arr.elements(); it.hasNext(); ) {
                JsonNode item = it.next();
                RTCIceServer srv = new RTCIceServer();
                JsonNode urls = item.path("urls");
                if (urls.isArray()) {
                    for (Iterator<JsonNode> it2 = urls.elements(); it2.hasNext(); ) srv.urls.add(it2.next().asText());
                } else if (urls.isTextual()) {
                    srv.urls.add(urls.asText());
                }
                if (item.hasNonNull("username")) srv.username = item.get("username").asText();
                if (item.hasNonNull("credential")) srv.password = item.get("credential").asText();
                cfg.iceServers.add(srv);
            }

            int ttl = Math.max(root.path("ttl").asInt(3600), MIN_REFRESH_SECONDS);
            long now = Instant.now().getEpochSecond();
            this.current = cfg;
            this.expiresAtEpochSeconds = now + ttl;

            logger.accept("ICE fetched: servers=" + cfg.iceServers.size() + " ttl=" + ttl + "s");
        } catch (Exception e) {
            logger.accept("ICE fetch failed: " + e.getMessage());
            if (current == null) throw new RuntimeException("No ICE config available", e);
            this.expiresAtEpochSeconds = Instant.now().getEpochSecond() + MIN_REFRESH_SECONDS;
        }
    }
}
