package org.example.webrtc;

import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceConnectionState;
import dev.onvoid.webrtc.RTCIceTransportPolicy;
import dev.onvoid.webrtc.RTCSignalingState;
import org.example.common.Frame;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.example.common.Config.ICE_BASE_URL;

public class WebRtcTransport implements Transport {

    private final RtcConfigProvider configProvider;
    private Listener listener;
    private String sessionId = "unassigned-" + UUID.randomUUID();

    private PeerConnectionManager pcMgr;
    private DataChannelIo dcIo;
    private SignalingService signaling;

    private final AtomicBoolean started = new AtomicBoolean(false);

    private RestartSignalingHandler restartHandler;

    public WebRtcTransport() {
        this.configProvider = new RtcConfigProvider(
                ICE_BASE_URL + "/ice?u=test",
                RTCIceTransportPolicy.RELAY
        );
    }

    public void setRestartHandler(RestartSignalingHandler h) {
        this.restartHandler = h;
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void setSessionId(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) this.sessionId = sessionId;
    }

    @Override
    public synchronized void start() {
        ensureNotStarted();
        Consumer<String> log = this::log;

        pcMgr = new PeerConnectionManager(configProvider, log);
        dcIo = new DataChannelIo(log, this::handleIncomingFrame);

        pcMgr.start(new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate c) {
            }

            @Override
            public void onIceConnectionChange(RTCIceConnectionState s) {
            }

            @Override
            public void onSignalingChange(RTCSignalingState s) {
            }

            @Override
            public void onDataChannel(RTCDataChannel ch) {
                dcIo.attach(ch);
            }
        });

        configProvider.startAutoRefresh((cfg, ttlSec) -> {
            try {
                pcMgr.pc().setConfiguration(cfg);
                log.accept("ICE config applied via setConfiguration(), ttl=" + ttlSec + "s");

                if (restartHandler != null) {
                    String offer = signaling.createOffer(true);
                    String answer = restartHandler.onOffer(offer);
                    signaling.setRemoteAnswer(answer);
                    log.accept("ICE restart completed");
                } else {
                    log.accept("restartHandler is null -> ICE restart skipped");
                }
            } catch (Exception e) {
                log.accept("refresh/restart failed: " + e.getMessage());
            }
        }, log);

        RTCDataChannel ch = pcMgr.createDataChannel();
        dcIo.attach(ch);

        signaling = new SignalingService(pcMgr.pc(), log);
        started.set(true);
        log("started");
    }

    @Override
    public synchronized void stop() {
        if (!started.get()) return;
        try {
            configProvider.stopAutoRefresh();
        } catch (Throwable ignored) {
        }
        pcMgr.stop();
        pcMgr = null;
        dcIo = null;
        signaling = null;
        started.set(false);
        log("stopped");
    }

    @Override
    public void open(int streamId, String host, int port) {
        ensureStarted();
        dcIo.ensureAttached();
        dcIo.sendFrame(Frame.connect(sessionId, streamId, host, port));
    }

    @Override
    public void send(int streamId, byte[] data) {
        ensureStarted();
        dcIo.ensureAttached();
        dcIo.sendFrame(Frame.data(sessionId, streamId, data));
    }

    @Override
    public void close(int streamId, String reason) {
        ensureStarted();
        dcIo.ensureAttached();
        dcIo.sendFrame(Frame.close(sessionId, streamId, reason));
    }

    @Override
    public void ack(int streamId, boolean ok, String reason) {
        ensureStarted();
        dcIo.ensureAttached();
        dcIo.sendFrame(Frame.connectAck(sessionId, streamId, ok, reason));
    }

    public String createOffer() {
        ensureStarted();
        return signaling.createOffer();
    }

    public void setRemoteAnswer(String sdp) {
        ensureStarted();
        signaling.setRemoteAnswer(sdp);
    }

    public void setRemoteOffer(String sdp) {
        ensureStarted();
        signaling.setRemoteOffer(sdp);
    }

    public String createAnswer() {
        ensureStarted();
        return signaling.createAnswer();
    }

    private void handleIncomingFrame(Frame f) {
        if (f == null || listener == null) return;
        switch (f.cmd) {
            case CONNECT_ACK -> listener.onConnectAck(f.streamId, f.ok, f.reason);
            case DATA -> listener.onData(f.streamId, f.payload != null ? f.payload : new byte[0]);
            case CLOSE -> listener.onClose(f.streamId, f.reason);
            case CONNECT -> {
                listener.onLog(sessionId + " :: CONNECT " + f.host + ":" + f.port + " streamId=" + f.streamId);
                listener.onIncomingConnect(f.streamId, f.host, f.port);
            }
        }
    }

    private void ensureNotStarted() {
        if (started.get()) throw new IllegalStateException("Transport already started");
    }

    private void ensureStarted() {
        if (!started.get()) throw new IllegalStateException("Transport is not started");
    }

    private void log(String s) {
        if (listener != null) listener.onLog(sessionId + " :: " + s);
        else System.out.println(sessionId + " :: " + s);
    }
}
