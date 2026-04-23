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
            AVCodec codec = avcodec_find_encoder(outputFormat.codec().ffmpegId);;
            codec_ctx = avcodec_alloc_context3(codec);
            //initOptions(codec_ctx);

            codec_ctx.pix_fmt(inputFormat.pixelFmt().ffmpegId);
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
        if (inputPacket != null) {
            AVPacket outputPacket = av_packet_alloc();
            avcodec_send_frame(codec_ctx, inputPacket);

            while (avcodec_receive_packet(codec_ctx, outputPacket) >= 0) {
                outQueue.add(av_packet_clone(outputPacket));
            }
        }
    }
}
