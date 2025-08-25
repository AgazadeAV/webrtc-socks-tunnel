package org.example.webrtc;

import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelObserver;
import org.example.common.Frame;
import org.example.common.JsonCodec;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

public final class DataChannelIo {

    interface FrameListener {
        void onFrame(Frame f);
    }

    private final Consumer<String> logger;
    private final FrameListener frameListener;
    private RTCDataChannel dc;

    DataChannelIo(Consumer<String> logger, FrameListener frameListener) {
        this.logger = logger;
        this.frameListener = frameListener;
    }

    void attach(RTCDataChannel channel) {
        this.dc = channel;
        channel.registerObserver(new RTCDataChannelObserver() {
            @Override
            public void onBufferedAmountChange(long prev) {
            }

            @Override
            public void onStateChange() {
                logger.accept("DataChannel state=" + channel.getState());
            }

            @Override
            public void onMessage(RTCDataChannelBuffer buffer) {
                try {
                    ByteBuffer data = buffer.data;
                    byte[] bytes;
                    if (data.hasArray()) {
                        int off = data.arrayOffset(), pos = data.position(), lim = data.limit();
                        bytes = java.util.Arrays.copyOfRange(data.array(), off + pos, off + lim);
                    } else {
                        ByteBuffer dup = data.slice();
                        bytes = new byte[dup.remaining()];
                        dup.get(bytes);
                    }
                    Frame f = JsonCodec.decodeBytes(bytes);
                    if (f != null) frameListener.onFrame(f);
                } catch (Exception e) {
                    logger.accept("onMessage parse error: " + e.getMessage());
                }
            }
        });
    }

    void ensureAttached() {
        if (dc == null) throw new IllegalStateException("DataChannel not attached");
    }

    void sendFrame(Frame f) {
        try {
            ensureAttached();
            byte[] json = JsonCodec.encodeBytes(f);
            RTCDataChannelBuffer buf = new RTCDataChannelBuffer(ByteBuffer.wrap(json), true);
            dc.send(buf);
        } catch (Exception e) {
            logger.accept("sendFrame error: " + e.getMessage());
        }
    }
}
