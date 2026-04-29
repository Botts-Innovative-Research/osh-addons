package org.sensorhub.impl.process.video.transcoder.coders;

import static org.bytedeco.ffmpeg.global.avutil.*;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.bytedeco.ffmpeg.global.avcodec.*;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.javacpp.BytePointer;
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
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "ffmpeg-codec-thread-" + coderNum));
    private final ExecutorService outputExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "ffmpeg-codec-output-thread-" + coderNum));
    private final Map<CoderCallback<O>, ExecutorService> callbackMap = new HashMap<>();

    CodecInfo inputFormat;
    CodecInfo outputFormat;
    protected AVCodecContext codec_ctx;
    protected AVCodec codec;
    protected I inPacket;
    protected O outPacket;
    protected final Queue<O> outQueue = new ArrayDeque<>(10);
    private final AtomicBoolean isProcessing = new AtomicBoolean(false); // Set false to indicate packets should no longer be accepted
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

        // All codec operations must happen in a separate thread
        try {
            executor.submit(() -> {
                initContext();
                initOptions();
                openContext();
                isProcessing.set(true);
            }).get(); // blocks constructor until init is complete
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during codec initialization", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Error initializing codec context", e.getCause());
        }
    }

    private void submitTask(Runnable task) {
        if (!executor.isShutdown()) {
            executor.submit(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    // Prevent silent thread replacement — log and propagate
                    // only after codec state is consistent
                    logger.error("Fatal error in codec runner thread", t);
                    //throw t; // will kill this task but not spawn a new thread silently
                }
            });
        }
    }

    public boolean isReady() {
        return isProcessing.get();
    }

    public Class getOutputClass() {
        return outputClass;
    }

    public Class getInputClass() {
        return inputClass;
    }

    protected abstract void initContext();

    protected void openContext() {
        int ret;
        if ((ret = avcodec_open2(codec_ctx, codec, (PointerPointer<?>) null)) < 0) {
            logFFmpeg(ret);
            throw new IllegalStateException("Error opening codec " + codec.name().getString());
        }
    }

    protected static void logFFmpeg(int retCode) {
        BytePointer buf = new BytePointer(AV_ERROR_MAX_STRING_SIZE);
        av_strerror(retCode, buf, buf.capacity());
        logger.warn("FFmpeg returned error code {}: {}", retCode, buf.getString());
    }

    /**
     * Set certain options in the codec context.
     * Context must be allocated first using {@link #initContext()}.
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

        codec_ctx.framerate(av_make_q(options.fps(), 1));

        if (inputFormat.codec().ffmpegId == AV_CODEC_ID_H264) {
            // OpenH264 only supports Baseline (66) and Main (77)
            codec_ctx.profile(AV_PROFILE_H264_MAIN);

            // Enable frame skip so bitrate control works correctly,
            // or it falls back to quality mode and ignores the bitrate setting
            av_opt_set(codec_ctx.priv_data(), "skip_frames", "1", 0);

            // OpenH264 uses slice_mode instead of preset
            av_opt_set(codec_ctx.priv_data(), "slice_mode", "auto", 0);
        }

        //av_opt_set(codec_ctx.priv_data(), "preset", options.preset(), 0);
        //av_opt_set(codec_ctx.priv_data(), "tune", options.tune(), 0);
        codec_ctx.strict_std_compliance(options.compliance()); // Needed so that yuvj420p works (used for mjpeg, must be set to unofficial)
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
            submitTask(() -> {
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

                // Submit cleanup *before* shutdown so it is the last task to run
                executor.submit(this::cleanup);
                executor.shutdown();
                try {
                    executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
                } catch (InterruptedException ignored) {
                    logger.warn("Interrupted while waiting for ffmpeg thread to finish");
                    Thread.currentThread().interrupt();
                }
                unregisterAllCallbacks();
            }
        }
    }

    private void cleanup() {
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
