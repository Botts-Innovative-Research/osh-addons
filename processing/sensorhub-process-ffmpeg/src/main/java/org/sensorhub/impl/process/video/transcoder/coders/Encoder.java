package org.sensorhub.impl.process.video.transcoder.coders;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.javacpp.BytePointer;
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
import static org.bytedeco.ffmpeg.global.swscale.sws_freeContext;

public class Encoder extends Coder<AVFrame, AVPacket> {

    public Encoder(CodecInfo inFormatInfo, CodecInfo outFormatInfo, CodecOptions options) {
        super(inFormatInfo, outFormatInfo, AVFrame.class, AVPacket.class, options);
    }

    @Override
    protected void initContext() {
        synchronized (contextLock) {
            // For H264, prefer x264 over OpenH264 — better option compatibility
            if (outputFormat.codec() == FullCodecEnum.H264) {
                codec = avcodec_find_encoder_by_name("libx264");
            }

            // Fall back to the default encoder for this codec ID
            if (codec == null || codec.isNull()) {
                codec = avcodec_find_encoder(outputFormat.codec().ffmpegId);
            }

            if (codec == null || codec.isNull()) {
                throw new IllegalStateException("Could not find encoder for: " + outputFormat.codec());
            }

            codec_ctx = avcodec_alloc_context3(codec);

            if (codec_ctx == null || codec_ctx.isNull()) {
                throw new IllegalStateException("Could not allocate encoder context for: " + codec.name().getString());
            }

            codec_ctx.pix_fmt(inputFormat.pixelFmt().ffmpegId);

            logger.debug("Using encoder: {}", codec.name().getString());
        }
    }

    @Override
    protected void deallocateInputPacket(AVFrame packet) {
        if (packet != null) {
            av_frame_free(packet);
        }
    }

    @Override
    protected void deallocateOutputPacket(AVPacket packet) {
        if (packet != null) {
            av_packet_free(packet);
        }
    }

    @Override
    protected AVPacket cloneOutput(AVPacket packet) {
        if (packet != null) {
            return av_packet_clone(packet);
        } else {
            return null;
        }
    }

    @Override
    protected void processInputPacket(AVFrame inputPacket) {
        if (inputPacket != null && !inputPacket.isNull()) {
            int ret;

            logger.debug("Sending frame to encoder: format={} width={} height={} pts={}",
                    inputPacket.format(),
                    inputPacket.width(),
                    inputPacket.height(),
                    inputPacket.pts());
            logger.debug("Encoder expects: format={} width={} height={}",
                    codec_ctx.pix_fmt(),
                    codec_ctx.width(),
                    codec_ctx.height());

            if ((ret = avcodec_send_frame(codec_ctx, inputPacket)) < 0) {
                logger.warn("Error sending packet to encoder");
                logFFmpeg(ret);
                //avcodec_flush_buffers(codec_ctx);
                return;
            }

            AVPacket outputPacket = av_packet_alloc();

            while (avcodec_receive_packet(codec_ctx, outputPacket) >= 0) {
                if (!outputPacket.isNull()) {
                    outQueue.add(outputPacket);
                }
                outputPacket = av_packet_alloc();
            }

            av_packet_free(outputPacket);
        }
    }
}
