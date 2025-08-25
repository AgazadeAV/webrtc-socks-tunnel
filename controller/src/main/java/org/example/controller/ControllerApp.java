package org.example.controller;

import org.example.common.signaling.Presence;
import org.example.common.signaling.SignalingProvider;
import org.example.webrtc.WebRtcTransport;

import java.time.Duration;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;

public class ControllerApp {

    private static final String SIGNAL_BASE_URL = "http://20.82.121.207:9090";
    private static final String SOCKS_HOST = "127.0.0.1";
    private static final int SOCKS_PORT = 1080;

    public static void main(String[] args) {
        SignalingProvider sp = new SignalingProvider(SIGNAL_BASE_URL);

        System.out.println("Controller CLI. Type 'help' for commands.");
        Scanner sc = new Scanner(System.in);

        while (true) {
            System.out.print("> ");
            String line = sc.hasNextLine() ? sc.nextLine().trim() : null;
            if (line == null) break;
            if (line.isEmpty()) continue;

            if (!handleCommand(line, sp)) {
                break;
            }
        }

        System.out.println("[Controller] stopped");
    }

    private static boolean handleCommand(String line, SignalingProvider sp) {
        switch (line) {
            case "help" -> printHelp();
            case "list" -> listAgents();
            case "quit", "exit" -> {
                System.out.println("Bye.");
                return false;
            }
            default -> {
                if (line.startsWith("connect")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length < 2) {
                        System.out.println("Usage: connect <agentId>");
                    } else {
                        String agentId = parts[1];
                        try {
                            connectAndRun(sp, agentId);
                        } catch (Exception e) {
                            System.out.println("Connect failed: " + e.getMessage());
                        }
                    }
                } else {
                    System.out.println("Unknown command. Type 'help'.");
                }
            }
        }
        return true;
    }

    private static void printHelp() {
        System.out.println("""
                Commands:
                  list                 - show active agents
                  connect <agentId>    - connect to agent
                  quit/exit            - exit controller
                """);
    }

    private static void listAgents() {
        List<String> agents = Presence.listAgents(SIGNAL_BASE_URL, 60);
        if (agents.isEmpty()) {
            System.out.println("No active agents found.");
        } else {
            System.out.println("Active agents:");
            for (int i = 0; i < agents.size(); i++) {
                System.out.printf("  %d) %s%n", i + 1, agents.get(i));
            }
        }
    }

    private static void connectAndRun(SignalingProvider sp, String agentId) throws Exception {
        WebRtcTransport transport = new WebRtcTransport();
        StreamRouter router = new StreamRouter(transport);

        configureTransport(transport, router);

        String sessionId = UUID.randomUUID().toString();
        String base = "sessions/" + agentId + "/" + sessionId + "/";

        setupRestartHandler(sp, transport, base);

        performInitialHandshake(sp, transport, base);

        startSocksServer(router);

        System.out.println("[Controller] Connected to agentId=" + agentId + ". Press Ctrl+C to exit");

        Thread.currentThread().join();
    }

    private static void configureTransport(WebRtcTransport transport, StreamRouter router) {
        transport.setListener(router);
        transport.setSessionId("controller-" + UUID.randomUUID());
        transport.start();
    }

    private static void setupRestartHandler(SignalingProvider sp, WebRtcTransport transport, String base) {
        transport.setRestartHandler(offerSdp -> {
            String ro = base + "restart/offer.sdp";
            String ra = base + "restart/answer.sdp";
            sp.delete(ro);
            sp.delete(ra);
            sp.putText(ro, offerSdp);
            System.out.println("[Controller] Restart offer uploaded");
            String ans = sp.waitAndGet(ra, Duration.ofMinutes(2), Duration.ofMillis(500));
            if (ans == null || ans.isBlank()) {
                throw new RuntimeException("Restart answer timeout/empty");
            }
            System.out.println("[Controller] Restart answer received");
            return ans;
        });
    }

    private static void performInitialHandshake(SignalingProvider sp, WebRtcTransport transport, String base) throws Exception {
        String offerKey = base + "offer.sdp";
        String answerKey = base + "answer.sdp";

        sp.delete(offerKey);
        sp.delete(answerKey);

        String sdpOffer = transport.createOffer();
        sp.putText(offerKey, sdpOffer);
        System.out.println("[Controller] Offer uploaded to " + offerKey);

        String sdpAnswer = sp.waitAndGet(answerKey, Duration.ofMinutes(3), Duration.ofSeconds(2));
        if (sdpAnswer == null || sdpAnswer.isBlank()) {
            throw new RuntimeException("No answer received from agent");
        }
        System.out.println("[Controller] Answer received from " + answerKey);

        transport.setRemoteAnswer(sdpAnswer);
    }

    private static void startSocksServer(StreamRouter router) throws Exception {
        Socks5Server socks = new Socks5Server(SOCKS_HOST, SOCKS_PORT, router);
        socks.start();
        System.out.println("[Controller] SOCKS5 on " + SOCKS_HOST + ":" + SOCKS_PORT);
    }
}
