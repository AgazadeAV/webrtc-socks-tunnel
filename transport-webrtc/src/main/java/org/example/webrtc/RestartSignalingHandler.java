package org.example.webrtc;

@FunctionalInterface
public interface RestartSignalingHandler {
    String onOffer(String offerSdp) throws Exception;
}
