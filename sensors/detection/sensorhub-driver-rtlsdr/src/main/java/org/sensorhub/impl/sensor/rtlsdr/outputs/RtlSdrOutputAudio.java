/***************************** BEGIN LICENSE BLOCK ***************************
 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2020-2026 Botts Innovative Research, Inc. All Rights Reserved.
 ******************************* END LICENSE BLOCK ***************************/
package org.sensorhub.impl.sensor.rtlsdr.outputs;

import net.opengis.swe.v20.*;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.impl.sensor.AbstractSensorOutput;
import org.sensorhub.impl.sensor.rtlsdr.RtlSdrDriver;
import org.sensorhub.impl.sensor.rtlsdr.dsp.RtlSdrAudioDemodulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vast.cdm.common.CDMException;
import org.vast.data.AbstractDataBlock;
import org.vast.data.DataBlockMixed;
import org.vast.swe.SWEConstants;
import org.vast.swe.SWEHelper;

import java.util.ArrayList;

/**
 * Connected Systems API streaming output for real-time demodulated audio from RTL-SDR.
 * Publishes 48 kHz 16-bit linear PCM audio frames conformant with OSH standards and OshViewer.
 */
public class RtlSdrOutputAudio extends AbstractSensorOutput<RtlSdrDriver> implements RtlSdrAudioDemodulator.AudioFrameListener {
    private static final Logger log = LoggerFactory.getLogger(RtlSdrOutputAudio.class);

    public static final String SENSOR_OUTPUT_NAME = "audioOutput";
    public static final String SENSOR_OUTPUT_LABEL = "Demodulated Audio";
    public static final String SENSOR_OUTPUT_DESCRIPTION = "Real-time demodulated audio stream from RTL-SDR receiver";
    private static final String NUM_SAMPLES_ID = "numSamplesID";

    public static final int AUDIO_SAMPLE_RATE = 48000;
    public static final String CODEC_NAME = "RAW"; // 16-bit Linear PCM audio

    private DataRecord dataStruct;
    private DataEncoding dataEncoding;

    private static final int MAX_NUM_TIMING_SAMPLES = 10;
    private final ArrayList<Double> intervalHistogram = new ArrayList<>(MAX_NUM_TIMING_SAMPLES);
    private final Object histogramLock = new Object();

    public RtlSdrOutputAudio(RtlSdrDriver parentSensor) {
        super(SENSOR_OUTPUT_NAME, parentSensor);
    }

    public void doInit() {
        log.info("Initializing RTL-SDR Audio Output");

        SWEHelper sweHelper = new SWEHelper();
        dataStruct = sweHelper.createRecord()
                .name(SENSOR_OUTPUT_NAME)
                .label(SENSOR_OUTPUT_LABEL)
                .description(SENSOR_OUTPUT_DESCRIPTION)
                .definition(SWEHelper.getPropertyUri("AudioFrame"))
                .addField("sampleTime", sweHelper.createTime()
                        .asSamplingTimeIsoUTC()
                        .label("Sample Time")
                        .description("Time of data collection"))
                .addField("sampleRate", sweHelper.createQuantity()
                        .label("Sample Rate")
                        .description("Number of audio samples per second")
                        .definition(SWEHelper.getQudtUri("DataRate"))
                        .uomCode("Hz")
                        .dataType(DataType.INT))
                .addField("channels", sweHelper.createCount()
                        .label("Channels")
                        .description("Number of audio channels (1=Mono, 2=Stereo)")
                        .dataType(DataType.INT))
                .addField("numSamples", sweHelper.createCount()
                        .id(NUM_SAMPLES_ID)
                        .label("Num Samples")
                        .description("Number of audio bytes/samples packaged in this record")
                        .dataType(DataType.INT))
                .addField("samples", sweHelper.createArray()
                        .withVariableSize(NUM_SAMPLES_ID)
                        .withElement("sample", sweHelper.createCount()
                                .label("Audio Sample")
                                .definition(SWEConstants.DEF_DN)
                                .dataType(DataType.BYTE)))
                .build();

        BinaryEncoding dataEnc = sweHelper.newBinaryEncoding(ByteOrder.BIG_ENDIAN, ByteEncoding.RAW);

        // 0: sampleTime
        BinaryComponent compTime = sweHelper.newBinaryComponent();
        compTime.setRef("/" + dataStruct.getComponent(0).getName());
        compTime.setCdmDataType(DataType.DOUBLE);
        dataEnc.addMemberAsComponent(compTime);

        // 1: sampleRate
        BinaryComponent compSampleRate = sweHelper.newBinaryComponent();
        compSampleRate.setRef("/" + dataStruct.getComponent(1).getName());
        compSampleRate.setCdmDataType(DataType.INT);
        dataEnc.addMemberAsComponent(compSampleRate);

        // 2: channels
        BinaryComponent compChannels = sweHelper.newBinaryComponent();
        compChannels.setRef("/" + dataStruct.getComponent(2).getName());
        compChannels.setCdmDataType(DataType.INT);
        dataEnc.addMemberAsComponent(compChannels);

        // 3: numSamples (byte count)
        BinaryComponent compNumSamples = sweHelper.newBinaryComponent();
        compNumSamples.setRef("/" + dataStruct.getComponent(3).getName());
        compNumSamples.setCdmDataType(DataType.INT);
        dataEnc.addMemberAsComponent(compNumSamples);

        // 4: samples (binary block)
        BinaryBlock audioBlock = sweHelper.newBinaryBlock();
        audioBlock.setRef("/" + dataStruct.getComponent(4).getName());
        audioBlock.setCompression(CODEC_NAME);
        dataEnc.addMemberAsBlock(audioBlock);

        try {
            SWEHelper.assignBinaryEncoding(dataStruct, dataEnc);
        } catch (CDMException e) {
            throw new RuntimeException("Invalid binary encoding configuration for RTL-SDR audio output", e);
        }

        this.dataEncoding = dataEnc;
    }

    @Override
    public synchronized void onAudioChunk(byte[] pcm16Data, int numSamples, String base64Pcm, int channels) {
        if (pcm16Data == null || pcm16Data.length == 0 || !parentSensor.isStarted()) {
            return;
        }

        try {
            long timestamp = System.currentTimeMillis();
            DataBlock dataBlock;
            if (latestRecord == null) {
                dataBlock = dataStruct.createDataBlock();
            } else {
                dataBlock = latestRecord.renew();
            }

            updateIntervalHistogram(timestamp);

            int index = 0;
            dataBlock.setDoubleValue(index++, timestamp / 1000.0);
            dataBlock.setIntValue(index++, AUDIO_SAMPLE_RATE);
            dataBlock.setIntValue(index++, channels > 0 ? channels : 1);
            dataBlock.setIntValue(index++, pcm16Data.length);

            // Set raw byte buffer in underlying data block
            AbstractDataBlock audioData = ((DataBlockMixed) dataBlock).getUnderlyingObject()[index];
            byte[] frameCopy = new byte[pcm16Data.length];
            System.arraycopy(pcm16Data, 0, frameCopy, 0, pcm16Data.length);
            audioData.setUnderlyingObject(frameCopy);

            latestRecord = dataBlock;
            latestRecordTime = timestamp;

            eventHandler.publish(new DataEvent(latestRecordTime, this, dataBlock));
        } catch (Exception e) {
            log.trace("Error publishing audio frame: {}", e.getMessage());
        }
    }

    @Override
    public DataComponent getRecordDescription() {
        return dataStruct;
    }

    @Override
    public DataEncoding getRecommendedEncoding() {
        return dataEncoding;
    }

    @Override
    public double getAverageSamplingPeriod() {
        synchronized (histogramLock) {
            if (intervalHistogram.isEmpty()) {
                return 0.05; // default 50ms (20 Hz audio frame dispatch)
            }
            double sum = 0;
            for (double s : intervalHistogram) {
                sum += s;
            }
            return sum / intervalHistogram.size();
        }
    }

    private void updateIntervalHistogram(long timestamp) {
        synchronized (histogramLock) {
            if (latestRecord != null && latestRecordTime != Long.MIN_VALUE) {
                long interval = timestamp - latestRecordTime;
                intervalHistogram.add(interval / 1000.0);
                if (intervalHistogram.size() > MAX_NUM_TIMING_SAMPLES) {
                    intervalHistogram.remove(0);
                }
            }
        }
    }
}
