package org.example.controller;

import org.example.common.signaling.S3SignalingProvider;
import org.example.common.signaling.SignalingProvider;
import org.example.webrtc.WebRtcTransport;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Duration;

public class ControllerApp {

    private static final String AWS_ACCESS_KEY = "AKIAUMUKCDOJ2IEBJYIS";
    private static final String AWS_SECRET_KEY = "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";
    private static final Region AWS_REGION = Region.US_EAST_2;
    private static final String S3_BUCKET = "tunnel-signal";

    private static final String SESSION_ID = "operator-01";
    private static final String SOCKS_HOST = "127.0.0.1";
    private static final int SOCKS_PORT = 1080;

    public static void main(String[] args) throws Exception {
        WebRtcTransport transport = new WebRtcTransport();

        StreamRouter router = new StreamRouter(transport);
        transport.setListener(router);
        transport.setSessionId(SESSION_ID);
        transport.start();

        S3Client s3 = buildS3();
        SignalingProvider sp = new S3SignalingProvider(s3, S3_BUCKET);

        transport.setRestartHandler(offerSdp -> {
            String ro = "sessions/" + SESSION_ID + "/restart/offer.sdp";
            String ra = "sessions/" + SESSION_ID + "/restart/answer.sdp";
            safeDelete(sp, ro);
            safeDelete(sp, ra);

            sp.putText(ro, offerSdp);
            System.out.println("[Controller] Restart offer uploaded");

            String ans = sp.waitAndGet(ra, Duration.ofMinutes(2), Duration.ofMillis(500));
            if (ans == null || ans.isBlank()) {
                throw new RuntimeException("Restart answer timeout/empty");
            }
            System.out.println("[Controller] Restart answer received");
            return ans;
        });

        String offerKey = "sessions/" + SESSION_ID + "/offer.sdp";
        String answerKey = "sessions/" + SESSION_ID + "/answer.sdp";

        safeDelete(sp, offerKey);
        safeDelete(sp, answerKey);

        String sdpOffer = transport.createOffer(false);
        sp.putText(offerKey, sdpOffer);
        System.out.println("[Controller] Offer uploaded to S3");

        String sdpAnswer = sp.waitAndGet(answerKey, Duration.ofMinutes(3), Duration.ofSeconds(2));
        System.out.println("[Controller] Answer received from S3");
        transport.setRemoteAnswer(sdpAnswer);

        Socks5Server socks = new Socks5Server(SOCKS_HOST, SOCKS_PORT, router);
        socks.start();
        System.out.println("[Controller] SOCKS5 on" + SOCKS_HOST + ":" + SOCKS_PORT);
        System.out.println("[Controller] Press Ctrl+C to exit");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                socks.stop();
            } catch (Exception ignored) {
            }
            try {
                transport.stop();
            } catch (Exception ignored) {
            }
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
