package org.sensorhub.impl.process.video.transcoder.coders;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.javacpp.PointerPointer;
import org.sensorhub.impl.process.video.transcoder.helpers.CodecInfo;
import org.sensorhub.impl.process.video.transcoder.helpers.CodecOptions;
import org.sensorhub.impl.process.video.transcoder.helpers.FullCodecEnum;
import org.sensorhub.impl.process.video.transcoder.helpers.FullPixelEnum;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Queue;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;

public class Decoder extends Coder<AVPacket, AVFrame> {

    public Decoder(CodecInfo inFormatInfo, CodecInfo outFormatInfo, CodecOptions options) {
        super(inFormatInfo, outFormatInfo, AVPacket.class, AVFrame.class, options);
    }

    @Override
    protected void initContext() {
        synchronized (contextLock) {
            codec = avcodec_find_decoder(inputFormat.codec().ffmpegId);
            codec_ctx = avcodec_alloc_context3(codec);
            codec_ctx.codec_id(inputFormat.codec().ffmpegId);
            codec_ctx.pix_fmt(outputFormat.pixelFmt().ffmpegId);
        }
    }

    @Override
    protected void deallocateInputPacket(AVPacket packet) {
        if (packet != null) {
            av_packet_free(packet);
        }
    }

    @Override
    protected void deallocateOutputPacket(AVFrame packet) {
        if (packet != null) {
            av_frame_free(packet);
        }
    }

    @Override
    protected AVFrame cloneOutput(AVFrame packet) {
        if (packet != null) {
            return av_frame_clone(packet);
        } else {
            return null;
        }
    }

    @Override
    protected void processInputPacket(AVPacket inputPacket) {
        if (inputPacket != null && !inputPacket.isNull()) {
            if (avcodec_send_packet(codec_ctx, inputPacket) < 0) {
                logger.warn("Error sending packet to decoder");
                //avcodec_flush_buffers(codec_ctx);
                return;
            }

            AVFrame outputPacket = av_frame_alloc();

            while (avcodec_receive_frame(codec_ctx, outputPacket) >= 0) {
                if (!outputPacket.isNull()) {
                    outQueue.add(outputPacket);
                }
                outputPacket = av_frame_alloc();
            }

            av_frame_free(outputPacket);
        }
    }
}
