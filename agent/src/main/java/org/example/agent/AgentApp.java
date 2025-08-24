package org.example.agent;

import org.example.common.Hostname;
import org.example.common.signaling.Mailbox;
import org.example.common.signaling.Presence;
import org.example.common.signaling.S3SignalingProvider;
import org.example.common.signaling.SignalingProvider;
import org.example.webrtc.Transport;
import org.example.webrtc.WebRtcTransport;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public class AgentApp {

    private static final String AWS_ACCESS_KEY = "AKIAUMUKCDOJ2IEBJYIS";
    private static final String AWS_SECRET_KEY = "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";
    private static final Region AWS_REGION = Region.US_EAST_2;
    private static final String S3_BUCKET = "tunnel-signal";

    private static final int CONNECT_TIMEOUT_MS = 15000;

    public static void main(String[] args) throws Exception {
        S3Client s3 = buildS3();
        SignalingProvider sp = new S3SignalingProvider(s3, S3_BUCKET);

        String AGENT_ID = Hostname.agentId();
        Presence.startHeartbeat(s3, S3_BUCKET, AGENT_ID, 15);
        System.out.println("[Agent] agentId=" + AGENT_ID + " is advertising presence");

        AtomicBoolean running = new AtomicBoolean(true);
        Thread me = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running.set(false);
            me.interrupt();
        }, "agent-shutdown"));

        while (running.get()) {
            System.out.println("[Agent] waiting for offers under sessions/" + AGENT_ID + "/<SESSION_ID>/offer.sdp ...");
            Mailbox.OfferRef off = Mailbox.waitNextOffer(s3, S3_BUCKET, AGENT_ID,
                    Duration.ofDays(365), Duration.ofMillis(300));
            if (off == null) continue;

            String sdpBase = off.key().substring(0, off.key().length() - "offer.sdp".length());
            String ro = sdpBase + "restart/offer.sdp";
            String ra = sdpBase + "restart/answer.sdp";

            WebRtcTransport transport = new WebRtcTransport();
            StreamRouter router = new StreamRouter(transport, new TcpDialer(CONNECT_TIMEOUT_MS));

            CountDownLatch sessionOver = new CountDownLatch(1);

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

            transport.setSessionId(AGENT_ID);
            transport.start();

            AtomicBoolean stopWatcher = new AtomicBoolean(false);
            Thread watcher = null;

            try {
                // SDP обмен
                String offerSdp = sp.waitAndGet(off.key(), Duration.ofSeconds(5), Duration.ofMillis(100));
                System.out.println("[Agent] Offer received from S3: " + off.key());
                transport.setRemoteOffer(offerSdp);

                String ans = transport.createAnswer(false);
                sp.putText(sdpBase + "answer.sdp", ans);
                System.out.println("[Agent] Answer uploaded to " + sdpBase + "answer.sdp");
                safeDelete(sp, off.key());

                safeDelete(sp, ro);
                safeDelete(sp, ra);
                watcher = startRestartWatcher(sp, transport, ro, ra, stopWatcher);

                System.out.println("[Agent] Session started. Waiting for disconnect...");
                try {
                    sessionOver.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    if (!running.get()) break;
                }
            } catch (Exception e) {
                System.out.println("[Agent] session error: " + e.getMessage());
            } finally {
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

        try {
            s3.close();
        } catch (Exception ignored) {
        }
        System.out.println("[Agent] stopped");
    }

    @SuppressWarnings("BusyWait")
    private static Thread startRestartWatcher(SignalingProvider sp, WebRtcTransport transport,
                                              String ro, String ra, AtomicBoolean stopFlag) {
        Thread t = new Thread(() -> {
            System.out.println("[Agent] Restart watcher started: ro=" + ro);
            while (!stopFlag.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    String offer = sp.waitAndGet(ro, Duration.ofSeconds(2), Duration.ofMillis(300));
                    if (offer != null && !offer.isBlank()) {
                        System.out.println("[Agent] Restart offer received");
                        transport.setRemoteOffer(offer);
                        String answer = transport.createAnswer(false);
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

    private static S3Client buildS3() {
        return S3Client.builder()
                .region(AWS_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(AWS_ACCESS_KEY, AWS_SECRET_KEY)
                ))
                .build();
    }

    private static void safeDelete(SignalingProvider sp, String key) {
        try {
            sp.delete(key);
        } catch (Exception ignored) {
        }
    }
}
