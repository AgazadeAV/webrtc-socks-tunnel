package org.example.common.signaling;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public final class Presence {
    private Presence() {
    }

    public static Thread startHeartbeat(S3Client s3, String bucket, String agentId, int periodSeconds) {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    s3.putObject(
                            PutObjectRequest.builder()
                                    .bucket(bucket)
                                    .key("agents/" + agentId + "/ready")
                                    .cacheControl("no-cache")
                                    .build(),
                            RequestBody.empty()
                    );
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
        return t;
    }
}
