package org.example.webrtc;

public interface RestartSignalingHandler {
    String onOffer(String offerSdp) throws Exception;
}
