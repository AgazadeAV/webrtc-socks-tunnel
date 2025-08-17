package org.example.webrtc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceGatheringState;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class SignalingService {

    private final RTCPeerConnection pc;
    private final Consumer<String> logger;
    private final ObjectMapper om = new ObjectMapper();
    private final List<RTCIceCandidate> pendingRemoteCandidates = new ArrayList<>();

    SignalingService(RTCPeerConnection pc, Consumer<String> logger) {
        this.pc = pc;
        this.logger = logger;
    }

    String createOffer() {
        RTCSessionDescription offer = join(supplyDesc(cb ->
                pc.createOffer(new RTCOfferOptions(), cb)
        ), "createOffer");

        join(run(cb -> pc.setLocalDescription(offer, cb)), "setLocal(offer)");
        awaitGatherOrCandidate();
        return pc.getLocalDescription().sdp;
    }

    String createAnswer() {
        RTCSessionDescription ans = join(supplyDesc(cb ->
                pc.createAnswer(new RTCAnswerOptions(), cb)
        ), "createAnswer");

        join(run(cb -> pc.setLocalDescription(ans, cb)), "setLocal(answer)");
        awaitGatherOrCandidate();
        return pc.getLocalDescription().sdp;
    }

    private void awaitGatherOrCandidate() {
        long deadline = System.currentTimeMillis() + (long) 60000;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            var ld = pc.getLocalDescription();
            boolean hasCandidate = ld != null && ld.sdp != null && ld.sdp.contains("\na=candidate:");
            if (hasCandidate || pc.getIceGatheringState() == RTCIceGatheringState.COMPLETE) return;
        }
        throw new RuntimeException("ICE gathering timeout (no candidates)");
    }


    void setRemoteAnswer(String sdp) {
        RTCSessionDescription ans = new RTCSessionDescription(RTCSdpType.ANSWER, sdp);
        join(run(cb -> pc.setRemoteDescription(ans, cb)), "setRemote(answer)");
        flushPendingCandidates();
    }

    void setRemoteOffer(String sdp) {
        RTCSessionDescription off = new RTCSessionDescription(RTCSdpType.OFFER, sdp);
        join(run(cb -> pc.setRemoteDescription(off, cb)), "setRemote(offer)");
        flushPendingCandidates();
    }

    void addRemoteIceCandidate(String candidateJsonOrLine) {
        try {
            RTCIceCandidate cand;
            String s = candidateJsonOrLine.trim();
            if (s.startsWith("{")) {
                JsonNode n = om.readTree(s);
                String candidate = n.path("candidate").asText(null);
                String sdpMid = n.hasNonNull("sdpMid") ? n.get("sdpMid").asText() : null;
                int sdpMLineIndex = n.hasNonNull("sdpMLineIndex") ? n.get("sdpMLineIndex").asInt() : 0;
                cand = new RTCIceCandidate(sdpMid, sdpMLineIndex, candidate);
            } else {
                cand = new RTCIceCandidate("data", 0, s);
            }
            if (!tryAdd(cand)) {
                synchronized (pendingRemoteCandidates) {
                    pendingRemoteCandidates.add(cand);
                }
            }
        } catch (Exception e) {
            logger.accept("addRemoteIceCandidate error: " + e.getMessage());
        }
    }

    private boolean tryAdd(RTCIceCandidate c) {
        try {
            pc.addIceCandidate(c);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void flushPendingCandidates() {
        synchronized (pendingRemoteCandidates) {
            for (RTCIceCandidate c : pendingRemoteCandidates) tryAdd(c);
            pendingRemoteCandidates.clear();
        }
    }

    private interface SDPOps {
        void call(CreateSessionDescriptionObserver cb);
    }

    private interface SetOps {
        void call(SetSessionDescriptionObserver cb);
    }

    private CompletableFuture<RTCSessionDescription> supplyDesc(SDPOps op) {
        CompletableFuture<RTCSessionDescription> fut = new CompletableFuture<>();
        op.call(new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription d) {
                fut.complete(d);
            }

            @Override
            public void onFailure(String e) {
                fut.completeExceptionally(new RuntimeException(e));
            }
        });
        return fut;
    }

    private CompletableFuture<Void> run(SetOps op) {
        CompletableFuture<Void> fut = new CompletableFuture<>();
        op.call(new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                fut.complete(null);
            }

            @Override
            public void onFailure(String e) {
                fut.completeExceptionally(new RuntimeException(e));
            }
        });
        return fut;
    }

    private static <T> T join(CompletableFuture<T> fut, String where) {
        try {
            return fut.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(where + " failed: " + e.getMessage(), e);
        }
    }
}
