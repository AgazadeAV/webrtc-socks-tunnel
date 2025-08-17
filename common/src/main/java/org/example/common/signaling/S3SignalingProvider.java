package org.example.common.signaling;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class S3SignalingProvider implements SignalingProvider {
    private final S3Client s3;
    private final String bucket;
    private final String prefix;

    public S3SignalingProvider(S3Client s3, String bucket, String prefix) {
        this.s3 = s3;
        this.bucket = bucket;
        this.prefix = (prefix == null || prefix.isBlank()) ? "" :
                (prefix.endsWith("/") ? prefix : prefix + "/");
    }

    private String key(String key) {
        return prefix + key;
    }

    @Override
    public void putText(String key, String content) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key(key))
                        .cacheControl("no-cache")
                        .contentType("text/plain; charset=utf-8")
                        .build(),
                RequestBody.fromBytes(content.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(key(key))
                .build());
    }

    public String waitAndGet(String key, Duration timeout, Duration interval) throws Exception {
        String fullKey = key(key);
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                return s3.getObjectAsBytes(b -> b.bucket(bucket).key(fullKey))
                        .asString(StandardCharsets.UTF_8);
            } catch (S3Exception e) {
                if (e.statusCode() != 404) throw e;
                Thread.sleep(interval.toMillis());
            }
        }
        throw new RuntimeException("Timeout waiting for s3://" + bucket + "/" + fullKey);
    }
}
