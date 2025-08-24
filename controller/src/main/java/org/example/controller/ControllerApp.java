package org.example.controller;

import org.example.common.signaling.S3SignalingProvider;
import org.example.common.signaling.SignalingProvider;
import org.example.webrtc.WebRtcTransport;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.UUID;

public class ControllerApp {

    private static final String AWS_ACCESS_KEY = "AKIAUMUKCDOJ2IEBJYIS";
    private static final String AWS_SECRET_KEY = "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";
    private static final Region AWS_REGION = Region.US_EAST_2;
    private static final String S3_BUCKET = "tunnel-signal";

    public static void main(String[] args) {
        S3Client s3 = buildS3();
        SignalingProvider sp = new S3SignalingProvider(s3, S3_BUCKET);

        System.out.println("Controller CLI. Type 'help' for commands.");
        Scanner sc = new Scanner(System.in);
        List<ActiveAgent> cache = Collections.emptyList();

        while (true) {
            System.out.print("> ");
            String line = sc.hasNextLine() ? sc.nextLine().trim() : null;
            if (line == null) break;
            if (line.isEmpty()) continue;

            String[] parts = line.split("\\s+");
            String cmd = parts[0].toLowerCase(Locale.ROOT);

            switch (cmd) {
                case "help" -> System.out.println("""
                            Commands:
                              list                 - show active agents
                              connect <N|agentId>  - connect to agent by number from 'list' or by exact agentId
                              refresh              - re-list agents (same as 'list')
                              quit/exit            - exit controller
                        """);
                case "list", "refresh" -> cache = printActiveAgents(s3);
                case "connect" -> {
                    if (parts.length < 2) {
                        System.out.println("Usage: connect <index|agentId>");
                        break;
                    }
                    String target = parts[1];

                    String agentId;
                    try {
                        int idx = Integer.parseInt(target);
                        if (idx >= 1 && idx <= cache.size()) {
                            agentId = cache.get(idx - 1).id;
                        } else {
                            System.out.println("Index out of range. Run 'list' first.");
                            break;
                        }
                    } catch (NumberFormatException nfe) {
                        agentId = target;
                    }

                    String finalAgentId = agentId;
                    if (agentId != null && cache.stream().noneMatch(a -> a.id.equals(finalAgentId))) {
                        List<ActiveAgent> fresh = listActiveAgentsDetailed(s3, Duration.ofSeconds(60));
                        boolean ok = fresh.stream().anyMatch(a -> a.id.equals(finalAgentId));
                        if (!ok) {
                            System.out.println("Agent '" + agentId + "' is not active. Use 'list' to see available ones.");
                            break;
                        }
                    }

                    System.out.println("[Controller] Connecting to agentId=" + agentId + " ...");
                    try {
                        connectAndRun(s3, sp, agentId);
                        return;
                    } catch (Exception e) {
                        System.out.println("Connect failed: " + e.getMessage());
                    }
                }
                case "quit", "exit" -> {
                    System.out.println("Bye.");
                    try {
                        s3.close();
                    } catch (Exception ignored) {
                    }
                    return;
                }
                default -> System.out.println("Unknown command. Type 'help'.");
            }
        }
    }

    private static S3Client buildS3() {
        return S3Client.builder()
                .region(AWS_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(AWS_ACCESS_KEY, AWS_SECRET_KEY)
                ))
                .build();
    }

    private static void safeDelete(SignalingProvider sp, String key) {
        try {
            sp.delete(key);
        } catch (Exception ignored) {
        }
    }

    private record ActiveAgent(String id, Instant lastModified) {
    }

    private static List<ActiveAgent> listActiveAgentsDetailed(S3Client s3, Duration maxAge) {
        Instant cutoff = Instant.now().minus(maxAge);
        List<S3Object> objs = s3.listObjectsV2(b -> b.bucket(S3_BUCKET).prefix("agents/")).contents();

        List<ActiveAgent> out = new ArrayList<>();
        for (S3Object o : objs) {
            String key = o.key();
            if (!key.endsWith("/ready")) continue;
            if (o.lastModified().isBefore(cutoff)) continue;
            String[] parts = key.split("/");
            if (parts.length >= 3) out.add(new ActiveAgent(parts[1], o.lastModified()));
        }
        out.sort((a, b) -> b.lastModified().compareTo(a.lastModified())); // свежие сверху
        return out;
    }

    private static String humanAge(Instant ts) {
        long sec = Math.abs(Duration.between(ts, Instant.now()).getSeconds());
        if (sec < 60) return sec + "s";
        long min = sec / 60;
        if (min < 60) return min + "m";
        long h = min / 60;
        return h + "h";
    }

    private static List<ActiveAgent> printActiveAgents(S3Client s3) {
        List<ActiveAgent> list = listActiveAgentsDetailed(s3, Duration.ofSeconds(60));
        if (list.isEmpty()) {
            System.out.println("No active agents found.");
        } else {
            System.out.println("Active agents:");
            for (int i = 0; i < list.size(); i++) {
                ActiveAgent a = list.get(i);
                System.out.printf("  %d) %s   (lastSeen %s ago)\n", i + 1, a.id, humanAge(a.lastModified));
            }
        }
        return list;
    }

    private static void connectAndRun(S3Client s3, SignalingProvider sp, String agentId) throws Exception {
        WebRtcTransport transport = new WebRtcTransport();
        StreamRouter router = new StreamRouter(transport);
        transport.setListener(router);
        transport.setSessionId("controller-" + UUID.randomUUID());
        transport.start();

        String SESSION_ID = UUID.randomUUID().toString();
        String base = "sessions/" + agentId + "/" + SESSION_ID + "/";

        transport.setRestartHandler(offerSdp -> {
            String ro = base + "restart/offer.sdp";
            String ra = base + "restart/answer.sdp";
            safeDelete(sp, ro);
            safeDelete(sp, ra);
            sp.putText(ro, offerSdp);
            System.out.println("[Controller] Restart offer uploaded");
            String ans = sp.waitAndGet(ra, Duration.ofMinutes(2), Duration.ofMillis(500));
            if (ans == null || ans.isBlank()) throw new RuntimeException("Restart answer timeout/empty");
            System.out.println("[Controller] Restart answer received");
            return ans;
        });

        String offerKey = base + "offer.sdp";
        String answerKey = base + "answer.sdp";
        safeDelete(sp, offerKey);
        safeDelete(sp, answerKey);

        String sdpOffer = transport.createOffer(false);
        sp.putText(offerKey, sdpOffer);
        System.out.println("[Controller] Offer uploaded to " + offerKey);

        String sdpAnswer = sp.waitAndGet(answerKey, Duration.ofMinutes(3), Duration.ofSeconds(2));
        System.out.println("[Controller] Answer received from " + answerKey);
        transport.setRemoteAnswer(sdpAnswer);

        final String SOCKS_HOST = "127.0.0.1";
        final int SOCKS_PORT = 1080;
        Socks5Server socks = new Socks5Server(SOCKS_HOST, SOCKS_PORT, router);
        socks.start();
        System.out.println("[Controller] SOCKS5 on " + SOCKS_HOST + ":" + SOCKS_PORT);
        System.out.println("[Controller] Connected to agentId=" + agentId + ". Press Ctrl+C to exit");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                socks.stop();
            } catch (Exception ignored) {
            }
            try {
                transport.stop();
            } catch (Exception ignored) {
            }
            try {
                s3.close();
            } catch (Exception ignored) {
            }
        }));

        try {
            Thread.currentThread().join();
        } catch (InterruptedException ignored) {
        }
    }
}
