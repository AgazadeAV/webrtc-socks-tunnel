package org.example.common;

public class Frame {
    public String sessionId;
    public int streamId;
    public CommandType cmd;
    public String host;
    public int port;
    public boolean ok;
    public String reason;
    public byte[] payload;

    public Frame() {
    }

    public static Frame connect(String sessionId, int streamId, String host, int port) {
        Frame frame = new Frame();
        frame.sessionId = sessionId;
        frame.streamId = streamId;
        frame.cmd = CommandType.CONNECT;
        frame.host = host;
        frame.port = port;
        return frame;
    }

    public static Frame connectAck(String sessionId, int streamId, boolean ok, String reason) {
        Frame frame = new Frame();
        frame.sessionId = sessionId;
        frame.streamId = streamId;
        frame.cmd = CommandType.CONNECT_ACK;
        frame.ok = ok;
        frame.reason = reason;
        return frame;
    }

    public static Frame data(String sessionId, int streamId, byte[] payload) {
        Frame frame = new Frame();
        frame.sessionId = sessionId;
        frame.streamId = streamId;
        frame.cmd = CommandType.DATA;
        frame.payload = payload;
        return frame;
    }

    public static Frame close(String sessionId, int streamId, String reason) {
        Frame frame = new Frame();
        frame.sessionId = sessionId;
        frame.streamId = streamId;
        frame.cmd = CommandType.CLOSE;
        frame.reason = reason;
        return frame;
    }

    public static Frame ping(String sessionId) {
        Frame frame = new Frame();
        frame.sessionId = sessionId;
        frame.cmd = CommandType.PING;
        return frame;
    }

    public static Frame pong(String sessionId) {
        Frame frame = new Frame();
        frame.sessionId = sessionId;
        frame.cmd = CommandType.PONG;
        return frame;
    }
}
