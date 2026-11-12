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

import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import net.opengis.swe.v20.DataRecord;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.impl.sensor.AbstractSensorOutput;
import org.sensorhub.impl.sensor.rtlsdr.RtlSdrDriver;
import org.vast.swe.SWEBuilders;
import org.vast.swe.SWEConstants;
import org.vast.swe.SWEHelper;

/**
 * Output specification and provider for the current settings of the RTL-SDR receiver.
 */
public class RtlSdrOutputSettings extends AbstractSensorOutput<RtlSdrDriver> {
    static final String SENSOR_OUTPUT_NAME = "rtlsdr_settings";
    static final String SENSOR_OUTPUT_LABEL = "Current Receiver Settings";
    static final String SENSOR_OUTPUT_DESCRIPTION = "Provides the active operating configuration and status of the RTL-SDR receiver";

    private DataRecord dataStruct;
    private DataEncoding dataEncoding;

    public RtlSdrOutputSettings(RtlSdrDriver parentSensor) {
        super(SENSOR_OUTPUT_NAME, parentSensor);
    }

    public void doInit() {
        SWEHelper sweFactory = new SWEHelper();

        SWEBuilders.DataRecordBuilder recordBuilder = sweFactory.createRecord()
                .name(SENSOR_OUTPUT_NAME)
                .label(SENSOR_OUTPUT_LABEL)
                .description(SENSOR_OUTPUT_DESCRIPTION)
                .definition(SWEHelper.getPropertyUri("rtlSdrSettingsOutput"))
                .addField("time", sweFactory.createTime()
                        .asSamplingTimeIsoUTC()
                        .label("Collection Time")
                        .description("Timestamp when current settings were recorded")
                        .definition(SWEConstants.DEF_SAMPLING_TIME))
                .addField("centerFreq", sweFactory.createQuantity()
                        .uomCode("MHz")
                        .label("Center Frequency")
                        .description("Currently tuned center frequency in MHz")
                        .definition(SWEConstants.QUDT_URI_PREFIX + "Frequency"))
                .addField("sampleRate", sweFactory.createQuantity()
                        .uomCode("Hz")
                        .label("Sample Rate")
                        .description("Active sample rate in samples/second")
                        .definition(SWEConstants.QUDT_URI_PREFIX + "Frequency"))
                .addField("tunerGain", sweFactory.createQuantity()
                        .uomCode("dB")
                        .label("Tuner Gain")
                        .description("Tuner gain in dB")
                        .definition(SWEHelper.getPropertyUri("UniformGain")))
                .addField("autoGain", sweFactory.createBoolean()
                        .label("Auto Gain")
                        .description("Indicates whether tuner automatic gain control is active")
                        .definition(SWEHelper.getPropertyUri("AutoGain")))
                .addField("agcMode", sweFactory.createBoolean()
                        .label("RTL AGC")
                        .description("Indicates whether RTL chip AGC is enabled")
                        .definition(SWEHelper.getPropertyUri("AgcMode")))
                .addField("ppmCorrection", sweFactory.createCount()
                        .label("PPM Correction")
                        .description("Frequency correction in parts per million")
                        .definition(SWEHelper.getPropertyUri("FrequencyCorrection")))
                .addField("streaming", sweFactory.createBoolean()
                        .label("Streaming Active")
                        .description("Indicates whether receiver is actively streaming samples")
                        .definition(SWEHelper.getPropertyUri("StreamingActive")));

        dataStruct = recordBuilder.build();
        dataEncoding = sweFactory.newTextEncoding(",", "\n");
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
        return 1.0;
    }

    public synchronized void publishSettings(double centerFreqMHz, int sampleRateHz, double tunerGaindB,
                                            boolean autoGain, boolean agcMode, int ppm, boolean isStreaming) {
        try {
            DataBlock dataBlock;
            if (latestRecord == null) {
                dataBlock = dataStruct.createDataBlock();
            } else {
                dataBlock = latestRecord.renew();
            }

            long now = System.currentTimeMillis();
            dataBlock.setDoubleValue(0, now / 1000.0);
            dataBlock.setDoubleValue(1, centerFreqMHz);
            dataBlock.setDoubleValue(2, sampleRateHz);
            dataBlock.setDoubleValue(3, tunerGaindB);
            dataBlock.setBooleanValue(4, autoGain);
            dataBlock.setBooleanValue(5, agcMode);
            dataBlock.setIntValue(6, ppm);
            dataBlock.setBooleanValue(7, isStreaming);

            latestRecord = dataBlock;
            latestRecordTime = now;

            eventHandler.publish(new DataEvent(latestRecordTime, RtlSdrOutputSettings.this, dataBlock));
        } catch (Exception e) {
            getLogger().error("Error publishing RTL-SDR settings: {}", e.getMessage());
        }
    }
}
