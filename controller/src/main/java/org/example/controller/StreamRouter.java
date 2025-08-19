package org.example.controller;

import io.netty.channel.Channel;
import org.example.webrtc.Transport;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class StreamRouter implements Transport.Listener {

    private final Transport transport;

    private final Map<Integer, Socks5Server.Socks5Handler> handlers = new ConcurrentHashMap<>();
    private final Map<Integer, Channel> channels = new ConcurrentHashMap<>();

    public StreamRouter(Transport transport) {
        this.transport = transport;
        this.transport.setListener(this);
    }

    public void open(int streamId, String host, int port, Channel clientChannel) {
        Socks5Server.Socks5Handler handler = clientChannel.pipeline().get(Socks5Server.Socks5Handler.class);
        handlers.put(streamId, handler);
        channels.put(streamId, clientChannel);

        transport.open(streamId, host, port);
    }

    public void send(int streamId, byte[] data) {
        transport.send(streamId, data);
    }

    public void close(int streamId, String reason) {
        transport.close(streamId, reason);
        Channel ch = channels.remove(streamId);
        handlers.remove(streamId);
        if (ch != null && ch.isActive()) ch.close();
    }

    @Override
    public void onConnectAck(int streamId, boolean ok, String message) {
        Socks5Server.Socks5Handler h = handlers.get(streamId);
        if (h == null) return;

        if (ok) {
            h.sendSuccess();
        } else {
            h.sendFailure(0x05);
            Channel ch = channels.remove(streamId);
            handlers.remove(streamId);
            if (ch != null && ch.isActive()) ch.close();
        }
    }

    @Override
    public void onData(int streamId, byte[] data) {
        Channel ch = channels.get(streamId);
        if (ch != null && ch.isActive()) {
            Socks5Server.Socks5Handler h = ch.pipeline().get(Socks5Server.Socks5Handler.class);
            if (h != null) h.sendData(data);
        }
    }

    @Override
    public void onClose(int streamId, String reason) {
        Channel ch = channels.remove(streamId);
        handlers.remove(streamId);
        if (ch != null && ch.isActive()) ch.close();
    }

    @Override
    public void onLog(String msg) {
        System.out.println("[Controller] " + msg);
    }
}
