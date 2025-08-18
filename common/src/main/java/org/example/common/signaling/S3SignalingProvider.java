package org.example.common.signaling;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class S3SignalingProvider implements SignalingProvider {
    private final S3Client s3;
    private final String bucket;

    public S3SignalingProvider(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public void putText(String key, String content) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .cacheControl("no-cache")
                        .contentType("text/plain; charset=utf-8")
                        .build(),
                RequestBody.fromBytes(content.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build());
    }

    @Override
    @SuppressWarnings("BusyWait")
    public String waitAndGet(String key, Duration timeout, Duration interval) throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                return s3.getObjectAsBytes(b -> b.bucket(bucket).key(key))
                        .asString(StandardCharsets.UTF_8);
            } catch (S3Exception e) {
                if (e.statusCode() != HttpURLConnection.HTTP_NOT_FOUND) throw e;
                Thread.sleep(interval.toMillis());
            }
        }
        throw new RuntimeException("Timeout waiting for s3://" + bucket + "/" + key);
    }
}
