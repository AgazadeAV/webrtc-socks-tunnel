package org.example.agent;

import org.example.webrtc.Transport;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StreamRouter implements Transport.Listener {

    private final Transport transport;
    private final TcpDialer dialer;

    private final Map<Integer, Socket> sockets = new ConcurrentHashMap<>();
    private final ExecutorService readers = Executors.newCachedThreadPool();

    public StreamRouter(Transport transport, TcpDialer dialer) {
        this.transport = transport;
        this.dialer = dialer;
    }

    public void shutdown() {
        readers.shutdownNow();
        sockets.values().forEach(s -> {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        });
        sockets.clear();
    }

    @Override
    public void onConnectAck(int streamId, boolean ok, String message) {
    }

    @Override
    public void onData(int streamId, byte[] data) {
        Socket s = sockets.get(streamId);
        if (s == null) return;
        try {
            OutputStream out = s.getOutputStream();
            out.write(data);
            out.flush();
        } catch (Exception e) {
            closeStream(streamId);
        }
    }

    @Override
    public void onClose(int streamId, String reason) {
        closeStream(streamId);
    }

    @Override
    public void onLog(String msg) {
        System.out.println("[Agent] " + msg);
    }

    @Override
    public void onIncomingConnect(int streamId, String host, int port) {
        try {
            Socket s = dialer.connect(host, port);
            sockets.put(streamId, s);

            readers.submit(() -> {
                try (Socket sock = s; InputStream in = sock.getInputStream()) {
                    byte[] buf = new byte[16 * 1024];
                    int r;
                    while ((r = in.read(buf)) != -1) {
                        byte[] chunk = new byte[r];
                        System.arraycopy(buf, 0, chunk, 0, r);
                        transport.send(streamId, chunk);
                    }
                } catch (Exception ignored) {
                } finally {
                    transport.close(streamId, "tcp-closed");
                    closeStream(streamId);
                }
            });

            transport.ack(streamId, true, "OK");
        } catch (Exception e) {
            transport.ack(streamId, false, "connect-failed: " + e.getMessage());
            transport.close(streamId, "connect-failed");
        }
    }

    private void closeStream(int streamId) {
        Socket s = sockets.remove(streamId);
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }
}
