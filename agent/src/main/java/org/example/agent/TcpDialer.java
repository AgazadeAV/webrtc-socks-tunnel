package org.example.agent;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

public class TcpDialer {
    private final int connectTimeoutMs;

    public TcpDialer(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public Socket connect(String host, int port) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        s.setTcpNoDelay(true);
        return s;
    }
}
