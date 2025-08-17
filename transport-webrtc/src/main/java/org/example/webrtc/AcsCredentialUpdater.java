package org.example.webrtc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class AcsCredentialUpdater {

    private static final String API_VERSION = "2022-03-01-preview";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);

    public static final class IceServer {
        public final List<String> urls;
        public final String username;
        public final String credential;

        public IceServer(List<String> urls, String username, String credential) {
            this.urls = Objects.requireNonNull(urls, "urls");
            this.username = username;
            this.credential = credential;
        }

        @Override
        public String toString() {
            return "IceServer{urls=" + urls + "}";
        }
    }

    public static final class IceBundle {
        public final List<IceServer> servers;
        public final Instant expiresOn;
        public final String rawJson;

        public IceBundle(List<IceServer> servers, Instant expiresOn, String rawJson) {
            this.servers = Objects.requireNonNull(servers, "servers");
            this.expiresOn = Objects.requireNonNull(expiresOn, "expiresOn");
            this.rawJson = rawJson;
        }

        public long ttlSeconds() {
            return Math.max(0, Duration.between(Instant.now(), expiresOn).getSeconds());
        }

        @Override
        public String toString() {
            return "IceBundle{ttl=" + ttlSeconds() + "s, servers=" + servers + "}";
        }
    }

    private final String tenantId;
    private final String clientId;
    private final String clientSecret;
    private final String acsEndpoint;

    private final HttpClient http;
    private final ObjectMapper om = new ObjectMapper();

    private final ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "acs-cred-updater");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<IceBundle> current = new AtomicReference<>();
    private final List<Consumer<IceBundle>> onUpdate = new CopyOnWriteArrayList<>();
    private final List<Consumer<Throwable>> onError = new CopyOnWriteArrayList<>();
    private final AtomicLong retryDelaySec = new AtomicLong(5);
    private volatile boolean started = false;

    private final long safetySec;
    private final long minRefreshSec;
    private final long maxRetrySec;

    public AcsCredentialUpdater(String tenantId,
                                String clientId,
                                String clientSecret,
                                String acsEndpoint,
                                long safetySec,
                                long minRefreshSec,
                                long maxRetrySec) {
        this(tenantId, clientId, clientSecret, acsEndpoint, safetySec, minRefreshSec, maxRetrySec, null);
    }

    public AcsCredentialUpdater(String tenantId,
                                String clientId,
                                String clientSecret,
                                String acsEndpoint,
                                long safetySec,
                                long minRefreshSec,
                                long maxRetrySec,
                                ProxySelector proxySelector) {

        this.tenantId = requireNonEmpty(tenantId, "tenantId");
        this.clientId = requireNonEmpty(clientId, "clientId");
        this.clientSecret = requireNonEmpty(clientSecret, "clientSecret");

        if (acsEndpoint == null || acsEndpoint.isBlank() || !acsEndpoint.startsWith("https://"))
            throw new IllegalArgumentException("acsEndpoint must start with https://");
        this.acsEndpoint = acsEndpoint.endsWith("/")
                ? acsEndpoint.substring(0, acsEndpoint.length() - 1)
                : acsEndpoint;

        if (safetySec < 1) throw new IllegalArgumentException("safetySec must be >= 1");
        if (minRefreshSec < 1) throw new IllegalArgumentException("minRefreshSec must be >= 1");
        if (maxRetrySec < 1) throw new IllegalArgumentException("maxRetrySec must be >= 1");

        this.safetySec = safetySec;
        this.minRefreshSec = minRefreshSec;
        this.maxRetrySec = maxRetrySec;

        HttpClient.Builder hb = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT);
        if (proxySelector != null) hb.proxy(proxySelector);
        this.http = hb.build();
    }

    public void onUpdate(Consumer<IceBundle> h) {
        if (h != null) onUpdate.add(h);
    }

    public void onError(Consumer<Throwable> h) {
        if (h != null) onError.add(h);
    }

    public synchronized void start() {
        if (started) return;
        started = true;
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "acs-cred-updater-shutdown"));
        ses.execute(this::refreshOnce);
    }

    public synchronized void stop() {
        if (!started) return;
        started = false;
        ses.shutdownNow();
    }

    public IceBundle current() {
        return current.get();
    }

    public IceBundle awaitFirst(Duration timeout) throws CredentialsUnavailableException, InterruptedException {
        long end = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < end) {
            IceBundle b = current.get();
            if (b != null) return b;
            Thread.sleep(25);
        }
        throw new CredentialsUnavailableException("No credentials received within " + timeout.toMillis() + " ms");
    }

    private void refreshOnce() {
        if (!started) return;
        try {
            IceBundle b = fetchFromAcs();
            current.set(b);
            retryDelaySec.set(5);
            for (var h : onUpdate) safe(() -> h.accept(b));
            scheduleNext(b);
        } catch (Throwable e) {
            for (var h : onError) safe(() -> h.accept(e));
            scheduleRetry();
        }
    }

    private void scheduleNext(IceBundle b) {
        if (!started) return;
        long ttl = b.ttlSeconds();
        long delay = Math.max(minRefreshSec, ttl - safetySec);
        if (delay <= 0) delay = 1;
        delay = withJitter(delay, 0.10);
        ses.schedule(this::refreshOnce, delay, TimeUnit.SECONDS);
    }

    private void scheduleRetry() {
        if (!started) return;
        long d = Math.min(maxRetrySec, retryDelaySec.get());
        d = withJitter(d, 0.20);
        retryDelaySec.updateAndGet(prev -> Math.min(maxRetrySec, Math.max(5, prev * 2)));
        ses.schedule(this::refreshOnce, d, TimeUnit.SECONDS);
    }

    private static long withJitter(long baseSec, double ratio) {
        double span = baseSec * ratio;
        double delta = (ThreadLocalRandom.current().nextDouble() * 2 - 1) * span;
        return Math.max(1, Math.round(baseSec + delta));
    }

    private static void safe(Runnable r) {
        try {
            r.run();
        } catch (Throwable ignore) {
        }
    }

    private static String requireNonEmpty(String v, String name) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException(name + " is blank");
        return v;
    }

    private IceBundle fetchFromAcs() throws AcsRequestException, AcsParseException, AcsTokenException {
        final String token = getAcsAccessToken(); // может бросить AcsTokenException

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(acsEndpoint + "/networkTraversal/:issueRelayConfiguration?api-version=" + API_VERSION))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .timeout(HTTP_TIMEOUT)
                .build();

        final HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new AcsRequestException("ACS request failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }

        if (resp.statusCode() / 100 != 2) {
            throw new AcsRequestException("ACS HTTP " + resp.statusCode() + ": " + resp.body());
        }

        try {
            JsonNode root = om.readTree(resp.body());

            String expStr = root.path("expiresOn").asText(null);
            if (expStr == null) throw new AcsParseException("Missing expiresOn in ACS response: " + resp.body());
            Instant exp = Instant.parse(expStr);

            List<IceServer> servers = new ArrayList<>();
            for (JsonNode n : root.withArray("iceServers")) {
                List<String> urls = new ArrayList<>();
                for (JsonNode u : n.withArray("urls")) urls.add(u.asText());
                String user = n.path("username").asText(null);
                String cred = n.path("credential").asText(null);
                servers.add(new IceServer(urls, user, cred));
            }

            if (servers.isEmpty()) {
                throw new AcsParseException("ACS returned empty iceServers: " + resp.body());
            }

            return new IceBundle(servers, exp, resp.body());
        } catch (AcsParseException e) {
            throw e;
        } catch (Exception e) {
            throw new AcsParseException("Failed to parse ACS response: " + e.getMessage(), e);
        }
    }

    private String getAcsAccessToken() throws AcsTokenException {
        String form = "grant_type=client_credentials" +
                "&client_id=" + enc(clientId) +
                "&client_secret=" + enc(clientSecret) +
                "&scope=" + enc("https://communication.azure.com//.default");

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://login.microsoftonline.com/" + tenantId + "/oauth2/v2.0/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .timeout(HTTP_TIMEOUT)
                .build();

        final HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new AcsTokenException("AAD token request failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }

        if (resp.statusCode() / 100 != 2) {
            throw new AcsTokenException("AAD token HTTP " + resp.statusCode() + ": " + resp.body());
        }

        try {
            return new ObjectMapper().readTree(resp.body()).path("access_token").asText(null);
        } catch (Exception e) {
            throw new AcsTokenException("Failed to parse AAD token response: " + e.getMessage(), e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        AcsCredentialUpdater up = new AcsCredentialUpdater(
                System.getenv("AZ_TENANT_ID"),
                System.getenv("AZ_CLIENT_ID"),
                System.getenv("AZ_CLIENT_SECRET"),
                System.getenv("ACS_ENDPOINT"),
                45, 60, 300
        );

        up.onUpdate(b -> System.out.println("[ACS] Updated: " + b));
        up.onError(e -> System.err.println("[ACS] Update error: " + e.getMessage()));

        up.start();

        try {
            IceBundle first = up.awaitFirst(Duration.ofSeconds(15));
            System.out.println("First creds TTL = " + first.ttlSeconds() + "s");
        } catch (CredentialsUnavailableException e) {
            System.err.println("Failed to obtain first credentials: " + e.getMessage());
        }
    }

    public static class AcsTokenException extends Exception {
        public AcsTokenException(String msg) {
            super(msg);
        }

        public AcsTokenException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    public static class AcsRequestException extends Exception {
        public AcsRequestException(String msg) {
            super(msg);
        }

        public AcsRequestException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    public static class AcsParseException extends Exception {
        public AcsParseException(String msg) {
            super(msg);
        }

        public AcsParseException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    public static class CredentialsUnavailableException extends Exception {
        public CredentialsUnavailableException(String msg) {
            super(msg);
        }
    }
}
