package org.sensorhub.impl.process.video.transcoder.coders;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.PointerPointer;
import org.sensorhub.impl.process.video.transcoder.helpers.CodecInfo;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Queue;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

public class SwScaler extends Coder<AVFrame, AVFrame> {
    long pts = 0;
    SwsContext swsContext;
    final int inWidth, inHeight, outWidth, outHeight;

    public SwScaler(CodecInfo inputFormat, CodecInfo outputFormat, int inWidth, int inHeight, int outWidth, int outHeight) {
        super(inputFormat, outputFormat, AVFrame.class, AVFrame.class, null);
        this.inWidth = inWidth;
        this.inHeight = inHeight;
        this.outWidth = outWidth;
        this.outHeight = outHeight;
    }

    @Override
    protected void initContext() {
        swsContext = sws_getContext(inWidth, inHeight, inputFormat.pixelFmt().ffmpegId,
                outWidth, outHeight, outputFormat.pixelFmt().ffmpegId,
                SWS_BICUBIC, null, null, (DoublePointer) null);
    }

    @Override
    protected void initOptions() {} // no options for swscaler

    @Override
    protected void deallocateInputPacket(AVFrame packet) {
        if (packet != null) {
            av_frame_free(packet);
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
    protected void processInputPacket(AVFrame inputPacket) {
        if (inputPacket != null) {
            AVFrame outputPacket = av_frame_alloc();
            sws_scale_frame(swsContext, outPacket, inPacket);
            outQueue.add(outputPacket);
        }
    }

    @Override
    public void close() {
        synchronized (contextLock) {
            super.close();
            sws_freeContext(swsContext);
        }
    }
}
