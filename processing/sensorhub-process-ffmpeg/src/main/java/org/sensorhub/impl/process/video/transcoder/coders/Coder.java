package org.sensorhub.impl.process.video.transcoder.coders;

import static org.bytedeco.ffmpeg.global.avutil.*;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.bytedeco.ffmpeg.global.avcodec.*;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.sensorhub.impl.process.video.transcoder.helpers.CodecInfo;
import org.sensorhub.impl.process.video.transcoder.helpers.CodecOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class Coder<I extends Pointer, O extends Pointer> implements AutoCloseable {


    public interface CoderCallback<O extends Pointer> {
        // The recipient does not need to deallocate the output; this is done automatically
        public abstract void onPacket(O packet);
    }

    protected static final Logger logger = LoggerFactory.getLogger(Coder.class);

    private static int coderCount = 0;
    private final int coderNum = coderCount++;
    private final ExecutorService submitExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "ffmpeg-codec-thread-" + coderNum));
    private final ExecutorService outputExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "ffmpeg-codec-output-thread-" + coderNum));
    private final Map<CoderCallback<O>, ExecutorService> callbackMap = new HashMap<>();

    CodecInfo inputFormat;
    CodecInfo outputFormat;
    protected AVCodecContext codec_ctx;
    protected AVCodec codec;
    protected I inPacket;
    protected O outPacket;
    protected final Queue<O> outQueue = new ArrayDeque<>(10);
    private final AtomicBoolean isProcessing = new AtomicBoolean(true); // Set false to indicate packets should no longer be accepted
    final Object contextLock = new Object();
    Class<I> inputClass;
    Class<O> outputClass;
    CodecOptions options;
    AtomicBoolean isNotifying = new AtomicBoolean(false);

    public Coder(CodecInfo inFormatInfo, CodecInfo outFormatInfo, Class<I> inputClass, Class<O> outputClass, CodecOptions options) {
        super();

        if ((inputClass != AVPacket.class && inputClass != AVFrame.class)
        || (outputClass != AVPacket.class && outputClass != AVFrame.class)) {
            throw new IllegalArgumentException("Input and output classes must be either AVPacket or AVFrame");
        }

        if (options == null)
            throw new IllegalArgumentException("Options cannot be null");

        this.inputFormat = inFormatInfo;
        this.inputClass = inputClass;
        this.outputFormat = outFormatInfo;
        this.outputClass = outputClass;
        this.options = options;

        submitExecutor.submit(() -> {
            initContext();
            initOptions();
            openContext();
        });
    }

    public Class getOutputClass() {
        return outputClass;
    }

    public Class getInputClass() {
        return inputClass;
    }

    protected abstract void initContext();

    protected void openContext() {
        if (avcodec_open2(codec_ctx, codec, (PointerPointer<?>) null) < 0) {
            throw new IllegalStateException("Error opening codec " + codec.name().getString());
        }
    }

    /**
     * Set certain options in the codec context.
     * @param codec_ctx Codec context. Context must be allocated first.
     */
    protected void initOptions() {

        codec_ctx.time_base(av_make_q(1, options.fps()));

        if (options.bitRate() > 0) {
            codec_ctx.bit_rate(options.bitRate() * 1000);
        } else {
            //codec_ctx.bit_rate(150*1000);
        }

        codec_ctx.width(options.width());
        codec_ctx.height(options.height());

        /*
        if (options.containsKey("pix_fmt")) {
            codec_ctx.pix_fmt(options.get("pix_fmt"));
        } else {
            if (inputFormat == AV_CODEC_ID_MJPEG) {
                codec_ctx.pix_fmt(AV_PIX_FMT_YUVJ420P);
            } else {
                codec_ctx.pix_fmt(AV_PIX_FMT_YUV420P);
            }
        }

         */

        av_opt_set(codec_ctx.priv_data(), "preset", options.preset(), 0);
        av_opt_set(codec_ctx.priv_data(), "tune", options.tune(), 0);
        codec_ctx.strict_std_compliance(options.compliance()); // Needed so that yuvj420p works (used for mjpeg)
    }

    protected abstract void deallocateInputPacket(I packet);
    protected abstract void deallocateOutputPacket(O packet);
    protected abstract O cloneOutput(O packet);
    protected abstract void processInputPacket(I inputPacket);

    // Take data from input queue and send to encoder/decoder
    public void submitInputPacket(I inputPacket) {
        synchronized (contextLock) {
            if (inputPacket == null || !isProcessing.get()) {
                return;
            }
            submitExecutor.submit(() -> {
                // Process the input
                processInputPacket(inputPacket);
                deallocateInputPacket(inputPacket);

                if (!outQueue.isEmpty() && isNotifying.compareAndSet(false, true)) {
                    outputExecutor.submit(() -> {
                        for (var outputPacket : outQueue) {
                            notifyCallbacks(outputPacket);
                        }
                    });
                    isNotifying.set(false);
                }
            });
        }
    }

    private void notifyCallbacks(O outputPacket) {
        for (var entry: callbackMap.entrySet()) {
            entry.getValue().submit(() -> {
                var clonedOutputPacket = cloneOutput(outputPacket);
                entry.getKey().onPacket(clonedOutputPacket);
                deallocateOutputPacket(clonedOutputPacket);
            });
        }
    }

    public void registerCallback(CoderCallback<O> callback) {
        if (!callbackMap.containsKey(callback)) {
            callbackMap.put(callback, Executors.newSingleThreadExecutor(r -> new Thread(r, "ffmpeg-codec-" + coderNum + "-callback-thread")));
        } else {
            logger.warn("This callback was already registered for codec " + coderNum);
        }
    }

    public void unregisterCallback(CoderCallback<O> callback) {
        callbackMap.remove(callback);
    }

    public void unregisterAllCallbacks() {
        callbackMap.clear();
    }

    @Override
    public void close() {
        synchronized (contextLock) {
            if (isProcessing.compareAndSet(true, false)) {
                submitExecutor.shutdownNow();
                outputExecutor.shutdownNow();

                if (codec_ctx != null) {
                    avcodec_free_context(codec_ctx);
                }
                codec_ctx = null;
                codec = null;

                unregisterAllCallbacks();

                for (var packet : outQueue) {
                    deallocateOutputPacket(packet);
                }
            }
        }
    }
}
