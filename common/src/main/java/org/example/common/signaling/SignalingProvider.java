package org.example.common.signaling;

public interface SignalingProvider {
    void putText(String key, String content);

    void delete(String key);
}
