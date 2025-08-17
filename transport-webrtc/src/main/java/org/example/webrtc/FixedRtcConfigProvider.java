package org.example.webrtc;

import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCIceTransportPolicy;

import java.util.Arrays;

public class FixedRtcConfigProvider implements RtcConfigProvider {

    private final String[] urls;
    private final String username;
    private final String password;
    private final RTCIceTransportPolicy policy;

    public FixedRtcConfigProvider(String[] urls, String username, String password,
                                  RTCIceTransportPolicy policy) {
        this.urls = urls;
        this.username = username;
        this.password = password;
        this.policy = policy;
    }

    @Override
    public RTCConfiguration get() {
        RTCConfiguration cfg = new RTCConfiguration();
        cfg.iceTransportPolicy = policy;

        RTCIceServer srv = new RTCIceServer();
        srv.urls.addAll(Arrays.asList(urls));
        srv.username = username;
        srv.password = password;

        cfg.iceServers.add(srv);
        return cfg;
    }
}
