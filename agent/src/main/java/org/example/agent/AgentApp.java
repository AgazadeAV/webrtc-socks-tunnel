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

    public static void main(String[] args) throws Exception {
        AgentConfig cfg = new AgentConfig();

        WebRtcTransport transport = new WebRtcTransport();

        StreamRouter router = new StreamRouter(cfg.getSessionId(), transport, new TcpDialer(cfg.getConnectTimeoutMs()));
        transport.setListener(router);
        transport.setSessionId(cfg.getSessionId());
        transport.start();

        try (S3Client s3 = buildS3()) {
            SignalingProvider sp = new S3SignalingProvider(s3, S3_BUCKET);
            String offerKey = "sessions/" + cfg.getSessionId() + "/offer.sdp";
            String answerKey = "sessions/" + cfg.getSessionId() + "/answer.sdp";

            safeDelete(sp, answerKey);

            String sdpOffer = sp.waitAndGet(offerKey, Duration.ofMinutes(3), Duration.ofSeconds(2));
            System.out.println("[Agent] Offer received from S3");
            transport.setRemoteOffer(sdpOffer);

            String sdpAnswer = transport.createAnswer(false);
            sp.putText(answerKey, sdpAnswer);
            System.out.println("[Agent] Answer uploaded to S3");
        }

        System.out.println("[Agent] started. Press Ctrl+C to exit");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                transport.stop();
            } catch (Exception ignored) {
            }
            router.shutdown();
        }));

        try {
            Thread.currentThread().join();
        } catch (InterruptedException ignored) {
        }
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
