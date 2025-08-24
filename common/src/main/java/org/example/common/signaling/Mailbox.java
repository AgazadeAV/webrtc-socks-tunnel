package org.example.common.signaling;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.time.Duration;

public final class Mailbox {
    private Mailbox() {
    }

    public record OfferRef(String sessionId, String key) {
    }

    public static OfferRef waitNextOffer(S3Client s3, String bucket, String agentId,
                                         Duration timeout, Duration poll) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        long sleep = Math.max(50L, poll.toMillis());
        String prefix = "sessions/" + agentId + "/";
        while (System.currentTimeMillis() < deadline) {
            ListObjectsV2Response resp = s3.listObjectsV2(
                    ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build()
            );
            for (S3Object o : resp.contents()) {
                String key = o.key();
                if (key.endsWith("/offer.sdp")) {
                    String[] parts = key.split("/");
                    if (parts.length >= 4) {
                        return new OfferRef(parts[2], key);
                    }
                }
            }
            Thread.sleep(sleep);
        }
        return null;
    }
}
