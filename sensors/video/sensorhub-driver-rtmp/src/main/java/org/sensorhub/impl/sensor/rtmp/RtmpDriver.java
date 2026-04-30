package org.sensorhub.impl.sensor.rtmp;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.sensor.SensorException;
import org.sensorhub.impl.sensor.AbstractSensorModule;
import org.sensorhub.impl.sensor.ffmpeg.outputs.AudioOutput;
import org.sensorhub.impl.sensor.ffmpeg.outputs.VideoOutput;
import org.sensorhub.impl.sensor.rtmp.config.HostType;
import org.sensorhub.impl.sensor.rtmp.config.RtmpConfig;
import org.sensorhub.mpegts.MpegTsProcessor;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class RtmpDriver extends AbstractSensorModule<RtmpConfig> {
    private static final RtmpUrlArbiter urlArbiter = new RtmpUrlArbiter();
    private ExecutorService executorService;
    private ExecutorService videoExecutorService;
    private ExecutorService audioExecutorService;
    private ScheduledExecutorService heartbeatExecutorService;

    final AtomicReference<MpegTsProcessor> mpegTsProcessor = new AtomicReference<>();
    final AtomicReference<VideoOutput<RtmpDriver>> videoOutput = new AtomicReference<>();
    final AtomicReference<AudioOutput<RtmpDriver>> audioOutput = new AtomicReference<>();
    String connectionUrl = "";
    volatile boolean hasConnected = false;


    @Override
    protected void doInit() throws SensorHubException {
        super.doInit();

        if (getUniqueIdentifier() == null) {
            generateUniqueID("urn:osh:sensor:rtmp:", config.serialNumber);
            generateXmlID("RTMP_", config.serialNumber);
        }

        urlArbiter.removeConnection(connectionUrl);

        setConnectionUrl();

        String moduleUid;
        if ((moduleUid = urlArbiter.addConnection(connectionUrl, this.getUniqueIdentifier())) != null) {
            throw new SensorException("RTMP url already in use by module: " + moduleUid);
        }

        createMpegTsProcessor();
    }

    private void createMpegTsProcessor() {
        String commandLineArgs = "-timeout 0 -listen 1";

        var mpegts = new MpegTsProcessor(connectionUrl, commandLineArgs);
        mpegts.setInjectVideoExtradata(true);
        mpegTsProcessor.set(mpegts);
    }

    private void setConnectionUrl() throws SensorException {
        var connectionConfig = config.connectionConfig;
        StringBuilder sb = new StringBuilder("rtmp://");

        if (connectionConfig.host == HostType.OVERRIDE) {
            if (connectionConfig.hostOverride == null || connectionConfig.hostOverride.isBlank()) {
                throw new SensorException("Domain override is not set");
            }
            sb.append(connectionConfig.hostOverride);
        } else {
            sb.append(connectionConfig.host.host);
        }

        sb.append(":").append(connectionConfig.port);

        if (connectionConfig.path != null && !connectionConfig.path.isBlank()) {
            if (!connectionConfig.path.startsWith("/")) {
                sb.append("/");
            }
            sb.append(connectionConfig.path.trim());
        }

        connectionUrl = sb.toString();
    }


    @Override
    protected void afterStart() throws SensorHubException {
        super.afterStart();
        //stopStream();
        hasConnected = false;

        if (mpegTsProcessor.get() == null) {
            createMpegTsProcessor();
        } else if (mpegTsProcessor.get().getState() != Thread.State.NEW) {
            stopStream();
            createMpegTsProcessor();
        }

        stopExecutors();
        executorService = Executors.newSingleThreadExecutor();
        executorService.submit(this::startStream);
        heartbeatExecutorService = Executors.newSingleThreadScheduledExecutor();
        heartbeatExecutorService.scheduleAtFixedRate(this::heartbeat, 5, 5, java.util.concurrent.TimeUnit.SECONDS);
        heartbeatExecutorService.submit(this::heartbeat);
    }

    private void startStream() {
        reportStatus("Listening on: " + connectionUrl);
        boolean status;

        var mpegts = mpegTsProcessor.get();

        if (mpegts == null) {
            logger.error("Could not start; stream processor is null");
            return;
        }
        status = mpegts.openStream();

        if (!status) {
            String error = "Failed to connect to " + connectionUrl;
            reportError(error, new SensorException(error));
            return;
        }

        synchronized (mpegTsProcessor) {
            mpegts = mpegTsProcessor.get();

            if (mpegts == null) {
                logger.error("Could not start; stream processor is null");
                return;
            }

            if (mpegts.isStreamOpened()) {
                if (mpegts.hasVideoStream()) {
                    createVideoOutput(mpegts.getVideoStreamFrameDimensions(), mpegts.getVideoCodecName());
                    mpegts.setVideoDataBufferListener(videoOutput.get());
                }

                if (mpegts.hasAudioStream()) {
                    createAudioOutput(mpegts.getAudioSampleRate(), mpegts.getAudioCodecName());
                    mpegts.setAudioDataBufferListener(audioOutput.get());
                }

            }
            clearStatus();
            reportStatus("RTMP stream for " + connectionUrl + " opened.");
            hasConnected = true;
            mpegts.processStream();
            /*
            executorService.submit(() -> {
                MpegTsProcessor processor;
                while ((processor = mpegTsProcessor.get()) != null) {

                    while (processor.isStreamOpened()) {
                        processor.processP();
                    }
                    if (!Thread.currentThread().isInterrupted()) {
                        reportStatus("RTMP stream " + connectionUrl + " lost connection. Reconnecting...");
                        processor.openStream();
                    } else {
                        return;
                    }
                }
                reportStatus("RTMP stream closed.");

            });

             */
            //mpegts.processStream();
        }
    }

    private void heartbeat() {
        var mpegts = mpegTsProcessor.get();
        if (mpegts == null || !hasConnected) { return; }

        if (!mpegts.isStreamOpened()) {
            reportStatus("RTMP stream " + connectionUrl + " lost connection. Reconnecting...");
            if (mpegts.openStream()) {
                reportStatus("RTMP stream for " + connectionUrl + " opened.");
            }
        }
    }

    protected void createVideoOutput(int[] videoDims, String codecName) {
        synchronized (videoOutput) {
            var videoOut = new VideoOutput<>(this, videoDims, codecName);
            videoOutput.set(videoOut);

            if (videoExecutorService != null) {
                videoExecutorService.shutdown();
            }

            videoExecutorService = Executors.newSingleThreadExecutor();
            videoOut.setExecutor(videoExecutorService);
            videoOut.doInit();
            addOutput(videoOut, false);
        }
    }

    protected void createAudioOutput(int sampleRate, String codecName) {
        synchronized (audioOutput) {
            var audioOut = new AudioOutput<>(this, sampleRate, codecName);
            audioOutput.set(audioOut);

            if (audioExecutorService != null) {
                audioExecutorService.shutdown();
            }
            audioExecutorService = Executors.newSingleThreadExecutor();
            audioOut.setExecutor(audioExecutorService);
            audioOut.doInit();
            addOutput(audioOut, false);
        }
    }


    @Override
    protected void doStop() throws SensorHubException {
        super.doStop();
        stopStream();
        stopExecutors();
        urlArbiter.removeConnection(connectionUrl);
    }

    private void stopStream() {
        synchronized (mpegTsProcessor) {
            var mpegts = mpegTsProcessor.get();
            if (mpegts != null) {
                mpegts.stopProcessingStream();
                try {
                    logger.info("Waiting for stream to stop.");
                    mpegts.join(10000);
                } catch (InterruptedException e) {
                    logger.error("Interrupted while waiting for stream to stop.", e);
                }
                mpegts.closeStream();
            }
            mpegTsProcessor.set(null);
        }
    }

    private void stopExecutors() {
        if (executorService != null) {
            executorService.shutdownNow();
        }
        if (videoExecutorService != null) {
            videoExecutorService.shutdownNow();
        }
        if (audioExecutorService != null) {
            audioExecutorService.shutdownNow();
        }
        if (heartbeatExecutorService != null) {
            heartbeatExecutorService.shutdownNow();
        }
    }


    @Override
    public void cleanup() throws SensorHubException {
        super.cleanup();
        stopStream();
        stopExecutors();
        urlArbiter.removeConnection(connectionUrl);
    }

    @Override
    public boolean isConnected() {
        return isStarted() && mpegTsProcessor.get() != null;
    }
}