package org.example.agent;

import org.example.common.Hostname;
import org.example.common.signaling.Mailbox;
import org.example.common.signaling.Presence;
import org.example.common.signaling.SignalingProvider;
import org.example.webrtc.Transport;
import org.example.webrtc.WebRtcTransport;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public class AgentApp {

    private static final String SIGNAL_BASE_URL = "http://20.82.121.207:9090";
    private static final int CONNECT_TIMEOUT_MS = 15000;

    public static void main(String[] args) {
        SignalingProvider sp = new SignalingProvider(SIGNAL_BASE_URL);
        String agentId = Hostname.agentId();

        startPresence(agentId);
        AtomicBoolean running = new AtomicBoolean(true);
        installShutdownHook(running);

        try {
            runAgentLoop(sp, agentId, running);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt(); // корректно восстанавливаем флаг
            System.out.println("[Agent] interrupted, shutting down...");
        } catch (Exception e) {
            System.out.println("[Agent] fatal error: " + e.getMessage());
        }

        System.out.println("[Agent] stopped");
    }

    private static void startPresence(String agentId) {
        Presence.startHeartbeat(SIGNAL_BASE_URL, agentId, 15);
        System.out.println("[Agent] agentId=" + agentId + " is advertising presence");
    }

    private static void installShutdownHook(AtomicBoolean running) {
        Thread me = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running.set(false);
            me.interrupt();
        }, "agent-shutdown"));
    }

    private static void runAgentLoop(SignalingProvider sp, String agentId, AtomicBoolean running) throws InterruptedException {
        while (running.get()) {
            System.out.println("[Agent] waiting for offers under sessions/" + agentId + "/<SESSION_ID>/offer.sdp ...");

            Mailbox.OfferRef off = Mailbox.waitNextOffer(
                    SIGNAL_BASE_URL, agentId,
                    Duration.ofDays(365), Duration.ofMillis(300)
            );
            if (off == null) continue;

            try {
                handleSession(sp, agentId, off, running);
            } catch (Exception e) {
                System.out.println("[Agent] session error: " + e.getMessage());
            }
        }
    }

    private static void handleSession(SignalingProvider sp, String agentId, Mailbox.OfferRef off, AtomicBoolean running) throws Exception {

        final String sdpBase = off.key().substring(0, off.key().length() - "offer.sdp".length());
        final String ro = sdpBase + "restart/offer.sdp";
        final String ra = sdpBase + "restart/answer.sdp";

        WebRtcTransport transport = new WebRtcTransport();
        StreamRouter router = new StreamRouter(transport, new TcpDialer(CONNECT_TIMEOUT_MS));
        CountDownLatch sessionOver = new CountDownLatch(1);
        configureTransportListener(transport, router, sessionOver);

        transport.setSessionId(agentId);
        transport.start();

        AtomicBoolean stopWatcher = new AtomicBoolean(false);
        Thread watcher = null;

        try {
            performInitialHandshake(sp, transport, off, sdpBase);

            safeDelete(sp, ro);
            safeDelete(sp, ra);
            watcher = startRestartWatcher(sp, transport, ro, ra, stopWatcher);

            System.out.println("[Agent] Session started. Waiting for disconnect...");
            awaitSessionOver(sessionOver, running);

        } finally {
            // Graceful teardown
            stopWatcher.set(true);
            if (watcher != null) watcher.interrupt();
            safeDelete(sp, ro);
            safeDelete(sp, ra);
            try {
                transport.stop();
            } catch (Exception ignored) {
            }
            router.shutdown();
        }
    }

    private static void configureTransportListener(WebRtcTransport transport, StreamRouter router, CountDownLatch sessionOver) {
        transport.setListener(new Transport.Listener() {
            @Override
            public void onConnectAck(int streamId, boolean ok, String message) {
                router.onConnectAck(streamId, ok, message);
            }

            @Override
            public void onData(int streamId, byte[] data) {
                router.onData(streamId, data);
            }

            @Override
            public void onClose(int streamId, String reason) {
                router.onClose(streamId, reason);
            }

            @Override
            public void onIncomingConnect(int streamId, String host, int port) {
                router.onIncomingConnect(streamId, host, port);
            }

            @Override
            public void onLog(String msg) {
                router.onLog(msg);
                if (msg.contains("ICE CLOSED") || msg.contains("ICE FAILED") || msg.contains("Signaling CLOSED")) {
                    sessionOver.countDown();
                }
            }
        });
    }

    private static void performInitialHandshake(SignalingProvider sp, WebRtcTransport transport, Mailbox.OfferRef off, String sdpBase) throws Exception {
        String offerSdp = sp.waitAndGet(off.key(), Duration.ofSeconds(5), Duration.ofMillis(100));
        System.out.println("[Agent] Offer received: " + off.key());

        transport.setRemoteOffer(offerSdp);

        String ans = transport.createAnswer();
        sp.putText(sdpBase + "answer.sdp", ans);
        System.out.println("[Agent] Answer uploaded to " + sdpBase + "answer.sdp");

        safeDelete(sp, off.key());
    }

    private static void awaitSessionOver(CountDownLatch sessionOver, AtomicBoolean running) {
        try {
            sessionOver.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            if (!running.get()) {
                System.out.println("[Agent] Shutdown requested, exiting session wait");
            }
        }
    }

    @SuppressWarnings("BusyWait")
    private static Thread startRestartWatcher(SignalingProvider sp, WebRtcTransport transport, String ro, String ra, AtomicBoolean stopFlag) {
        Thread t = new Thread(() -> {
            System.out.println("[Agent] Restart watcher started: ro=" + ro);
            while (!stopFlag.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    String offer = sp.waitAndGet(ro, Duration.ofSeconds(2), Duration.ofMillis(300));
                    if (offer != null && !offer.isBlank()) {
                        System.out.println("[Agent] Restart offer received");
                        transport.setRemoteOffer(offer);
                        String answer = transport.createAnswer();
                        safeDelete(sp, ra);
                        sp.putText(ra, answer);
                        System.out.println("[Agent] Restart answer uploaded");
                        safeDelete(sp, ro);
                    }
                } catch (Exception e) {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
            System.out.println("[Agent] Restart watcher stopped: ro=" + ro);
        }, "restart-watcher");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void safeDelete(SignalingProvider sp, String key) {
        try {
            sp.delete(key);
        } catch (Exception ignored) {
        }
    }
}
