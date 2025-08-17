package org.example.webrtc;

public interface Transport {

    interface Listener {
        void onConnectAck(int streamId, boolean ok, String message);

        void onData(int streamId, byte[] data);

        void onClose(int streamId, String reason);

        default void onLog(String msg) {
        }

        default void onIncomingConnect(int streamId, String host, int port) {
        }
    }

    void setListener(Listener listener);

    void setSessionId(String sessionId);

    void start();

    void stop();

    void open(int streamId, String host, int port);

    void send(int streamId, byte[] data);

    void close(int streamId, String reason);

    void ack(int streamId, boolean ok, String reason);
}
