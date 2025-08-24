package org.example.common;

import java.net.InetAddress;

public final class Hostname {
    private Hostname() {
    }

    public static String agentId() {
        String hn = System.getenv("COMPUTERNAME");
        if (hn == null || hn.isBlank()) hn = System.getenv("HOSTNAME");
        if (hn == null || hn.isBlank()) {
            try {
                hn = InetAddress.getLocalHost().getHostName();
            } catch (Exception ignored) {
            }
        }
        if (hn == null || hn.isBlank()) hn = "unknown-agent";
        return hn.toLowerCase().replaceAll("[^a-z0-9._-]", "_");
    }
}
