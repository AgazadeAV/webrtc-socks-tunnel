package org.example.webrtc;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelInit;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceConnectionState;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCSignalingState;

import java.util.function.Consumer;

public final class PeerConnectionManager {

    private final RtcConfigProvider configProvider;
    private final Consumer<String> logger;
    private PeerConnectionFactory factory;
    private RTCPeerConnection pc;

    PeerConnectionManager(RtcConfigProvider configProvider, Consumer<String> logger) {
        this.configProvider = configProvider;
        this.logger = logger;
    }

    void start(PeerConnectionObserver externalObserver) {
        if (pc != null) throw new IllegalStateException("PC already started");
        factory = new PeerConnectionFactory(null, null);
        pc = factory.createPeerConnection(configProvider.get(), wrapObserver(externalObserver));
        logger.accept("pc: started");
    }

    void stop() {
        try {
            if (pc != null) pc.close();
        } catch (Throwable ignored) {
        }
        pc = null;
        factory = null;
        logger.accept("pc: stopped");
    }

    RTCPeerConnection pc() {
        if (pc == null) throw new IllegalStateException("PC not started");
        return pc;
    }

    RTCDataChannel createDataChannel() {
        RTCDataChannelInit init = new RTCDataChannelInit();
        return pc().createDataChannel("tunnel", init);
    }

    private PeerConnectionObserver wrapObserver(PeerConnectionObserver upstream) {
        return new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate c) {
                if (c != null) {
                    logger.accept("ICE candidate mid=" + c.sdpMid + " mline=" + c.sdpMLineIndex);
                    upstream.onIceCandidate(c);
                } else {
                    logger.accept("ICE candidate: end-of-candidates");
                }
            }

            @Override
            public void onIceConnectionChange(RTCIceConnectionState s) {
                logger.accept("ICE " + s);
                upstream.onIceConnectionChange(s);
            }

            @Override
            public void onSignalingChange(RTCSignalingState s) {
                logger.accept("Signaling " + s);
                upstream.onSignalingChange(s);
            }

            @Override
            public void onDataChannel(RTCDataChannel ch) {
                logger.accept("onDataChannel: " + ch.getLabel());
                upstream.onDataChannel(ch);
            }

            @Override
            public void onIceGatheringChange(dev.onvoid.webrtc.RTCIceGatheringState s) {
                logger.accept("ICE-GATHER " + s);
                upstream.onIceGatheringChange(s);
            }
        };
    }
}
