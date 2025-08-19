package org.example.agent;

import org.example.common.signaling.S3SignalingProvider;
import org.example.common.signaling.SignalingProvider;
import org.example.webrtc.WebRtcTransport;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Duration;

public class AgentApp {

    private static final String AWS_ACCESS_KEY = "AKIAUMUKCDOJ2IEBJYIS";
    private static final String AWS_SECRET_KEY = "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";
    private static final Region AWS_REGION = Region.US_EAST_2;
    private static final String S3_BUCKET = "tunnel-signal";

    private static final String SESSION_ID = "operator-01";
    private static final int CONNECT_TIMEOUT_MS = 15000;

    public static void main(String[] args) throws Exception {
        WebRtcTransport transport = new WebRtcTransport();

        StreamRouter router = new StreamRouter(transport, new TcpDialer(CONNECT_TIMEOUT_MS));
        transport.setListener(router);
        transport.setSessionId(SESSION_ID);
        transport.start();

        S3Client s3 = buildS3();
        SignalingProvider sp = new S3SignalingProvider(s3, S3_BUCKET);

        String offerKey = "sessions/" + SESSION_ID + "/offer.sdp";
        String answerKey = "sessions/" + SESSION_ID + "/answer.sdp";

        safeDelete(sp, answerKey);

        String sdpOffer = sp.waitAndGet(offerKey, Duration.ofMinutes(3), Duration.ofSeconds(2));
        System.out.println("[Agent] Offer received from S3");
        transport.setRemoteOffer(sdpOffer);

        String sdpAnswer = transport.createAnswer(false);
        sp.putText(answerKey, sdpAnswer);
        System.out.println("[Agent] Answer uploaded to S3");

        startRestartWatcher(sp, transport);

        System.out.println("[Agent] started. Press Ctrl+C to exit");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                transport.stop();
            } catch (Exception ignored) {
            }
            router.shutdown();
            try {
                s3.close();
            } catch (Exception ignored) {
            }
        }));

        try {
            Thread.currentThread().join();
        } catch (InterruptedException ignored) {
        }
    }

    @SuppressWarnings("BusyWait")
    private static void startRestartWatcher(SignalingProvider sp, WebRtcTransport transport) {
        final String ro = "sessions/" + SESSION_ID + "/restart/offer.sdp";
        final String ra = "sessions/" + SESSION_ID + "/restart/answer.sdp";

        Thread t = new Thread(() -> {
            System.out.println("[Agent] Restart watcher started");
            while (true) {
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
                        Thread.sleep(500);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }, "restart-watcher");
        t.setDaemon(true);
        t.start();
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
