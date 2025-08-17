package org.example.controller;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

class Socks5Server {
    private final String bindHost;
    private final int bindPort;
    private final StreamRouter router;

    private EventLoopGroup boss;
    private EventLoopGroup worker;
    private Channel server;

    public Socks5Server(String bindHost, int bindPort, StreamRouter router) {
        this.bindHost = bindHost;
        this.bindPort = bindPort;
        this.router = router;
    }

    public void start() throws InterruptedException {
        boss = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        worker = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        ServerBootstrap b = new ServerBootstrap();
        b.group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline().addLast(new Socks5Handler(router));
                    }
                });
        server = b.bind(bindHost, bindPort).sync().channel();
    }

    public void stop() throws InterruptedException {
        if (server != null) server.close().sync();
        if (boss != null) boss.shutdownGracefully();
        if (worker != null) worker.shutdownGracefully();
    }

    static class Socks5Handler extends ChannelInboundHandlerAdapter {
        private enum State {HANDSHAKE, REQUEST, STREAM}

        private State state = State.HANDSHAKE;

        private final StreamRouter router;
        private int streamId;
        private ChannelHandlerContext ctx;
        private static final AtomicInteger STREAM_SEQ = new AtomicInteger(100);

        Socks5Handler(StreamRouter router) {
            this.router = router;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf buf = (ByteBuf) msg;
            try {
                if (state == State.HANDSHAKE) {
                    if (buf.readableBytes() < 2) return;
                    buf.readByte();
                    int nMethods = buf.readByte() & 0xFF;
                    if (buf.readableBytes() < nMethods) return;
                    buf.skipBytes(nMethods);
                    ctx.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{0x05, 0x00}));
                    state = State.REQUEST;

                } else if (state == State.REQUEST) {
                    if (buf.readableBytes() < 4) return;
                    buf.readByte();
                    int cmd = buf.readByte() & 0xFF;
                    buf.readByte();
                    int atyp = buf.readByte() & 0xFF;

                    if (cmd != 0x01) {
                        sendFailure(0x07);
                        return;
                    }

                    String host;
                    if (atyp == 0x01) {
                        if (buf.readableBytes() < 4 + 2) return;
                        host = (buf.readByte() & 0xFF) + "." + (buf.readByte() & 0xFF) + "." +
                                (buf.readByte() & 0xFF) + "." + (buf.readByte() & 0xFF);
                    } else if (atyp == 0x03) {
                        if (buf.readableBytes() < 1) return;
                        int len = buf.readByte() & 0xFF;
                        if (buf.readableBytes() < len + 2) return;
                        byte[] dom = new byte[len];
                        buf.readBytes(dom);
                        host = new String(dom, StandardCharsets.UTF_8);
                    } else {
                        sendFailure(0x08);
                        return;
                    }

                    int port = buf.readUnsignedShort();

                    streamId = STREAM_SEQ.incrementAndGet();
                    router.open(streamId, host, port, ctx.channel());

                    state = State.STREAM;

                } else if (state == State.STREAM) {
                    byte[] arr = new byte[buf.readableBytes()];
                    buf.readBytes(arr);
                    router.send(streamId, arr);
                }
            } finally {
                buf.release();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (streamId != 0) router.close(streamId, "client-closed");
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (streamId != 0) router.close(streamId, "exception: " + cause.getMessage());
            ctx.close();
        }

        void sendFailure(int rep) {
            byte[] resp = new byte[]{0x05, (byte) rep, 0x00, 0x01, 0, 0, 0, 0, 0, 0};
            ctx.writeAndFlush(Unpooled.wrappedBuffer(resp)).addListener(ChannelFutureListener.CLOSE);
        }

        void sendSuccess() {
            byte[] resp = new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0};
            ctx.writeAndFlush(Unpooled.wrappedBuffer(resp));
        }

        void sendData(byte[] bytes) {
            ctx.writeAndFlush(Unpooled.wrappedBuffer(bytes));
        }
    }
}
