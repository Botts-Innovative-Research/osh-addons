/***************************** BEGIN LICENSE BLOCK ***************************
 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2026 Botts Innovative Research, Inc. All Rights Reserved.
 ******************************* END LICENSE BLOCK ***************************/
package org.sensorhub.impl.sensor.gopro;

import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.impl.sensor.ffmpeg.FFMPEGSensor;
import org.sensorhub.impl.sensor.ffmpeg.outputs.AudioOutput;
import org.sensorhub.impl.sensor.ffmpeg.outputs.VideoOutput;
import org.sensorhub.impl.sensor.gopro.config.GoProConfig;
import org.sensorhub.impl.sensor.gopro.gpmf.GpmfDispatcher;
import org.sensorhub.impl.sensor.gopro.outputs.GpmfOutput;
import org.sensorhub.impl.sensor.gopro.outputs.GpsOutput;
import org.sensorhub.mpegts.StreamContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Sensor driver for GoPro cameras and GoPro recordings.
 * <p>
 * GoPro sources carry more than the single video and single audio track the {@link FFMPEGSensor} base driver
 * publishes: dual-lens models (MAX, Fusion) record two video tracks, some models record two audio tracks, and
 * every model since the HERO5 records a GPMF telemetry track. This driver overrides
 * {@link FFMPEGSensor#initializeOutputs()} to walk every stream in the source and publish an output per stream.
 * <p>
 * The first video and audio outputs are named {@code video} and {@code audio} so that they line up with the base
 * driver; additional tracks are suffixed with their ordinal ({@code video2}, {@code audio2}, and so on).
 */
public class GoProSensor extends FFMPEGSensor {
    /**
     * Codec FourCC tag that GoPro MP4 recordings use for the GPMF telemetry track.
     */
    private static final String GPMF_CODEC_TAG = "gpmd";

    /**
     * Stream handler name that GoPro MP4 recordings use for the GPMF telemetry track.
     */
    private static final String GPMF_HANDLER_NAME = "gopro met";

    private static final String UID_PREFIX = "urn:osh:sensor:gopro:";
    private static final String XML_ID_PREFIX = "GOPRO_";

    /**
     * All video outputs, ordered by stream ID. The first entry is also held in {@link FFMPEGSensor#videoOutput}.
     */
    private final List<VideoOutput<FFMPEGSensor>> videoOutputs = new ArrayList<>();

    /**
     * All audio outputs, ordered by stream ID. The first entry is also held in {@link FFMPEGSensor#audioOutput}.
     */
    private final List<AudioOutput<FFMPEGSensor>> audioOutputs = new ArrayList<>();

    /**
     * All GPMF telemetry outputs, ordered by stream ID.
     */
    private final List<GpmfOutput<FFMPEGSensor>> gpmfOutputs = new ArrayList<>();

    /**
     * All GPS position outputs, ordered by stream ID. Empty when GPS publishing is turned off.
     */
    private final List<GpsOutput<FFMPEGSensor>> gpsOutputs = new ArrayList<>();

    /**
     * The listener registered on each GPMF stream, which decodes a packet once and feeds every output
     * interested in it. A stream context holds only one listener, so the outputs go through these.
     */
    private final List<GpmfDispatcher> gpmfDispatchers = new ArrayList<>();

    /**
     * Used when this driver is instantiated programmatically with a plain {@link org.sensorhub.impl.sensor.ffmpeg.config.FFMPEGConfig}
     * rather than through the module registry, so that the GoPro-specific settings still have their defaults.
     */
    private GoProConfig defaultConfig;

    @Override
    protected void doInit() throws SensorHubException {
        // beforeInit() has already dropped every output, so drop our references to them as well
        // before the base class re-creates them by way of initializeOutputs().
        videoOutputs.clear();
        audioOutputs.clear();
        gpmfOutputs.clear();
        gpsOutputs.clear();
        gpmfDispatchers.clear();

        super.doInit();

        // Re-brand the IDs the base driver generated. afterInit() has not run yet, so this is still in time.
        generateUniqueID(UID_PREFIX, config.serialNumber);
        generateXmlID(XML_ID_PREFIX, config.serialNumber);

        logger.info("Initialized GoPro sensor for {}", getUniqueIdentifier());
    }

    /**
     * Creates an output for every video, audio, and GPMF telemetry stream the source carries, and registers
     * each output as the listener for its own stream.
     * <p>
     * Outputs are reused across stop/start cycles: on restart the stream contexts are rebuilt by the stream
     * processor, so the listeners have to be re-registered, but the outputs themselves stay valid.
     */
    @Override
    protected void initializeOutputs() {
        var streamContexts = mpegTsProcessor.getStreamCollection().getStreamContexts();

        int videoCount = 0;
        int audioCount = 0;
        int gpmfCount = 0;

        for (StreamContext streamContext : streamContexts) {
            // The collection is sized to the stream count and may hold gaps if a stream was removed
            if (streamContext == null)
                continue;

            switch (streamContext.getStreamType()) {
                case VIDEO -> attachVideoOutput(streamContext, videoCount++);
                case AUDIO -> attachAudioOutput(streamContext, audioCount++);
                case DATA -> {
                    if (isGpmfStream(streamContext))
                        attachGpmfOutput(streamContext, gpmfCount++);
                    else
                        logger.info("Ignoring data stream {} (codec {}, tag '{}', handler '{}'): not GPMF telemetry",
                                streamContext.getStreamId(), streamContext.getCodecName(),
                                streamContext.getCodecTagString(), streamContext.getHandlerName());
                }
                default -> logger.debug("Ignoring stream {} of type {}",
                        streamContext.getStreamId(), streamContext.getStreamType());
            }
        }

        logger.info("Initialized {} video, {} audio, and {} GPMF telemetry output(s) for {}",
                videoCount, audioCount, gpmfCount, getUniqueIdentifier());

        if (videoCount == 0 && audioCount == 0 && gpmfCount == 0)
            reportStatus("No video, audio, or GPMF telemetry streams found in " + config.connection.connectionString);
    }

    /**
     * Also hands the executor to the outputs the base class does not know about.
     */
    @Override
    protected void setupExecutor() {
        super.setupExecutor();

        if (executor == null)
            return;

        videoOutputs.forEach(output -> output.setExecutor(executor));
        audioOutputs.forEach(output -> output.setExecutor(executor));
        // The GPMF outputs are fed by their dispatcher, which is what does the executor hop
        gpmfDispatchers.forEach(dispatcher -> dispatcher.setExecutor(executor));
    }

    /**
     * @return All video outputs of this driver, ordered by stream ID.
     */
    public List<VideoOutput<FFMPEGSensor>> getVideoOutputs() {
        return Collections.unmodifiableList(videoOutputs);
    }

    /**
     * @return All audio outputs of this driver, ordered by stream ID.
     */
    public List<AudioOutput<FFMPEGSensor>> getAudioOutputs() {
        return Collections.unmodifiableList(audioOutputs);
    }

    /**
     * @return All GPMF telemetry outputs of this driver, ordered by stream ID.
     */
    public List<GpmfOutput<FFMPEGSensor>> getGpmfOutputs() {
        return Collections.unmodifiableList(gpmfOutputs);
    }

    /**
     * @return All GPS position outputs of this driver, ordered by stream ID. Empty when GPS publishing
     * is turned off or the source carries no GPMF telemetry.
     */
    public List<GpsOutput<FFMPEGSensor>> getGpsOutputs() {
        return Collections.unmodifiableList(gpsOutputs);
    }

    /**
     * Creates the video output for the given stream if it does not exist yet, then registers it as the
     * listener for that stream.
     *
     * @param streamContext The video stream to publish.
     * @param index         Zero-based ordinal of this stream among the video streams of the source.
     */
    private void attachVideoOutput(StreamContext streamContext, int index) {
        if (index >= videoOutputs.size()) {
            String name = outputName("video", index);

            var output = new VideoOutput<FFMPEGSensor>(this, streamContext.getFrameDimensions(),
                    streamContext.getCodecName(), name,
                    outputLabel("Video", index),
                    "Video stream " + streamContext.getStreamId() + " (" + streamContext.getCodecName() + ") using ffmpeg library");

            if (executor != null)
                output.setExecutor(executor);

            addOutput(output, false);
            output.doInit();
            videoOutputs.add(output);

            // Keep the base class field pointing at the primary video output
            if (videoOutput == null)
                videoOutput = output;

            logger.info("Created video output '{}' for stream {} ({}, {}x{})", name, streamContext.getStreamId(),
                    streamContext.getCodecName(), streamContext.getFrameWidth(), streamContext.getFrameHeight());
        }

        streamContext.setDataBufferListener(videoOutputs.get(index));
    }

    /**
     * Creates the audio output for the given stream if it does not exist yet, then registers it as the
     * listener for that stream.
     *
     * @param streamContext The audio stream to publish.
     * @param index         Zero-based ordinal of this stream among the audio streams of the source.
     */
    private void attachAudioOutput(StreamContext streamContext, int index) {
        if (index >= audioOutputs.size()) {
            String name = outputName("audio", index);

            var output = new AudioOutput<FFMPEGSensor>(this, streamContext.getSampleRate(),
                    streamContext.getCodecName(), name,
                    outputLabel("Audio", index),
                    "Audio stream " + streamContext.getStreamId() + " (" + streamContext.getCodecName() + ") using ffmpeg library");

            if (executor != null)
                output.setExecutor(executor);

            addOutput(output, false);
            output.doInit();
            audioOutputs.add(output);

            // Keep the base class field pointing at the primary audio output
            if (audioOutput == null)
                audioOutput = output;

            logger.info("Created audio output '{}' for stream {} ({}, {} Hz)", name, streamContext.getStreamId(),
                    streamContext.getCodecName(), streamContext.getSampleRate());
        }

        streamContext.setDataBufferListener(audioOutputs.get(index));
    }

    /**
     * Creates the GPMF telemetry output for the given stream if it does not exist yet, then registers it as
     * the listener for that stream.
     *
     * @param streamContext The GPMF telemetry stream to publish.
     * @param index         Zero-based ordinal of this stream among the GPMF streams of the source.
     */
    private void attachGpmfOutput(StreamContext streamContext, int index) {
        if (index >= gpmfDispatchers.size()) {
            String telemetryName = outputName("gpmf", index);
            var dispatcher = new GpmfDispatcher(telemetryName);

            var telemetryOutput = new GpmfOutput<FFMPEGSensor>(this, telemetryName,
                    outputLabel("GPMF Telemetry", index),
                    "GoPro GPMF telemetry from stream " + streamContext.getStreamId());

            addOutput(telemetryOutput, false);
            telemetryOutput.doInit();
            gpmfOutputs.add(telemetryOutput);
            dispatcher.addListener(telemetryOutput);

            // The GPS fixes are also reported by the telemetry output, but a location vector is what
            // lets the rest of OpenSensorHub treat them as a position rather than as loose numbers.
            if (getGoProConfig().publishGps) {
                String gpsName = outputName("gps", index);

                var gpsOutput = new GpsOutput<FFMPEGSensor>(this, getGoProConfig().altitudeDatum,
                        getGoProConfig().requireGpsFix, gpsName,
                        outputLabel("GPS", index),
                        "GoPro GPS fixes from stream " + streamContext.getStreamId());

                addOutput(gpsOutput, false);
                gpsOutput.doInit();
                gpsOutputs.add(gpsOutput);
                dispatcher.addListener(gpsOutput);

                logger.info("Created GPS output '{}' for stream {} with {} altitude", gpsName,
                        streamContext.getStreamId(), getGoProConfig().altitudeDatum.getLabel());
            }

            if (executor != null)
                dispatcher.setExecutor(executor);

            gpmfDispatchers.add(dispatcher);

            logger.info("Created GPMF telemetry output '{}' for stream {} (codec {}, tag '{}')", telemetryName,
                    streamContext.getStreamId(), streamContext.getCodecName(), streamContext.getCodecTagString());
        }

        streamContext.setDataBufferListener(gpmfDispatchers.get(index));
    }

    /**
     * Determines whether a data stream carries GPMF telemetry.
     * <p>
     * GoPro MP4 recordings mark the telemetry track with the {@code gpmd} codec tag and a {@code GoPro MET}
     * handler name. Sources that drop those markers can be published as GPMF anyway with the
     * {@link GoProConfig#assumeUntaggedDataIsGpmf} setting.
     *
     * @param streamContext The data stream to test.
     * @return {@code true} if an output should be created for this stream, {@code false} otherwise.
     */
    protected boolean isGpmfStream(StreamContext streamContext) {
        if (!getGoProConfig().publishGpmf)
            return false;

        if (GPMF_CODEC_TAG.equalsIgnoreCase(streamContext.getCodecTagString()))
            return true;

        String handlerName = streamContext.getHandlerName();
        if (handlerName != null && handlerName.toLowerCase().contains(GPMF_HANDLER_NAME))
            return true;

        return getGoProConfig().assumeUntaggedDataIsGpmf;
    }

    /**
     * Returns the GoPro-specific configuration, falling back to defaults when this driver was instantiated
     * with a plain FFmpeg configuration.
     */
    protected GoProConfig getGoProConfig() {
        if (config instanceof GoProConfig goProConfig)
            return goProConfig;

        if (defaultConfig == null)
            defaultConfig = new GoProConfig();

        return defaultConfig;
    }

    /**
     * Builds the output name for a stream, leaving the first stream of each kind unsuffixed so that it
     * matches the name the base driver would have used.
     */
    private static String outputName(String baseName, int index) {
        return index == 0 ? baseName : baseName + (index + 1);
    }

    /**
     * Builds the output label for a stream, leaving the first stream of each kind unsuffixed.
     */
    private static String outputLabel(String baseLabel, int index) {
        return index == 0 ? baseLabel : baseLabel + " " + (index + 1);
    }
}
