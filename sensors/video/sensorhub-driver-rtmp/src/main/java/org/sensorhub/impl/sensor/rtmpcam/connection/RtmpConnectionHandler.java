package org.sensorhub.impl.sensor.rtmpcam.connection;

import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.*;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.util.Optional;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;

/**
 * Handles one RTMP connection:
 *   1. Handshake
 *   2. AMF0 negotiation → {@link RtmpConnectionContext}
 *   3. Route to a matching {@link RtmpListener}
 *   4. RTMP chunks → FLV pipe → FFmpeg custom AVIO
 *   5. Deliver encoded packets via {@link RtmpListener#publish}
 */
class RtmpConnectionHandler {

    private static final int AVIO_BUF = 64 * 1024;
    private static final Logger logger = LoggerFactory.getLogger(RtmpConnectionHandler.class);

    private final Socket              socket;
    private final int                 port;
    private final RtmpListenerManager manager;

    RtmpConnectionHandler(Socket socket, int port, RtmpListenerManager manager) {
        this.socket  = socket;
        this.port    = port;
        this.manager = manager;
    }

    void handle() {
        try (socket) {
            var in  = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());

            // One negotiator instance owns all state across all three phases
            RtmpNegotiator negotiator = new RtmpNegotiator(in, out, port);

            negotiator.doHandshake();
            RtmpConnectionContext ctx = negotiator.negotiate();

            Optional<RtmpListener> match = manager.route(ctx);
            if (match.isEmpty()) {
                // No listener registered for this combination — drop silently
                System.out.printf("[RTMP:%d] No listener for path='%s' key='%s'%n",
                        port, ctx.path(), ctx.streamKey());
                return;
            }

            RtmpListener listener = match.get();
            listener.onConnected(ctx);
            try {
                pipeToFfmpeg(negotiator.buildFlvStream(), listener);
            } finally {
                listener.onDisconnected();
            }

        } catch (Exception e) {
            System.err.printf("[RTMP:%d] connection fault: %s%n", port, e.getMessage());
        }
    }

    // ── FFmpeg pipeline ────────────────────────────────────────────────────

    private void pipeToFfmpeg(InputStream flvStream, RtmpListener listener) {

        // Both must stay reachable for the pipeline's lifetime:
        //   readCb  — stored as a raw native function pointer inside AVIOContext
        //   avioBuf — FFmpeg takes ownership; free via ctx.buffer(), not this reference
        Read_packet_Pointer_BytePointer_int readCb  = buildReadCb(flvStream);
        BytePointer             avioBuf = new BytePointer(av_malloc(AVIO_BUF)).capacity(AVIO_BUF);

        AVIOContext avioCtx = avio_alloc_context(
                avioBuf, AVIO_BUF,
                0,       // read-only
                (Pointer) null,    // opaque
                (Read_packet_Pointer_BytePointer_int) readCb, (Write_packet_Pointer_BytePointer_int) null, (Seek_Pointer_long_int) null); // no write, no seek (live stream)

        AVFormatContext fmtCtx = avformat_alloc_context();
        fmtCtx.pb(avioCtx);  // must be set before avformat_open_input

        int ret = avformat_open_input(fmtCtx, (String) null,
                av_find_input_format("flv"), null);

        if (ret < 0) { logError("avformat_open_input", ret); freeAVIO(avioCtx); return; }

        avformat_find_stream_info(fmtCtx, (AVDictionary) null);
        packetLoop(fmtCtx, listener);

        avformat_close_input(fmtCtx);
        freeAVIO(avioCtx);
        // readCb and avioBuf are now safe to collect
    }

    private void packetLoop(AVFormatContext fmtCtx, RtmpListener listener) {
        AVPacket pkt = av_packet_alloc();
        try {
            int ret;
            while ((ret = av_read_frame(fmtCtx, pkt)) >= 0) {
                AVStream stream = fmtCtx.streams(pkt.stream_index());
                int mediaType   = stream.codecpar().codec_type();
                if (mediaType == AVMEDIA_TYPE_VIDEO || mediaType == AVMEDIA_TYPE_AUDIO) {
                    byte[] data = new byte[pkt.size()];
                    pkt.data().get(data);
                    listener.publish(data, pkt.stream_index(), pkt.pts(),
                            mediaType == AVMEDIA_TYPE_VIDEO);
                }
                av_packet_unref(pkt);
            }
            if (ret != AVERROR_EOF) logError("av_read_frame", ret);
        } finally {
            av_packet_free(pkt);
        }
    }

    private Read_packet_Pointer_BytePointer_int buildReadCb(InputStream src) {
        byte[] tmp = new byte[AVIO_BUF];
        return new Read_packet_Pointer_BytePointer_int() {
            @Override
            public int call(Pointer opaque, BytePointer dst, int requested) {
                try {
                    int n = src.read(tmp, 0, Math.min(requested, tmp.length));
                    if (n <= 0) return AVERROR_EOF;
                    dst.put(tmp, 0, n);
                    return n;
                } catch (IOException e) {
                    return AVERROR_EOF;
                }
            }
        };
    }

    private static void freeAVIO(AVIOContext ctx) {
        if (ctx == null || ctx.isNull()) return;
        BytePointer buf = ctx.buffer();
        if (buf != null && !buf.isNull()) av_freep(buf);
        avio_context_free(ctx);
    }

    private static void logError(String fn, int code) {
        try (BytePointer buf = new BytePointer(128)) {
            av_strerror(code, buf, buf.capacity());
            logger.warn("FFmpeg returned error code {} from {}: {}", code, fn, buf.getString());
        }
    }
}