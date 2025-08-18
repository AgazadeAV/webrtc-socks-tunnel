package org.example.common.signaling;

import java.time.Duration;

public interface SignalingProvider {
    void putText(String key, String content);

    void delete(String key);

    String waitAndGet(String key, Duration timeout, Duration interval) throws Exception;
}
