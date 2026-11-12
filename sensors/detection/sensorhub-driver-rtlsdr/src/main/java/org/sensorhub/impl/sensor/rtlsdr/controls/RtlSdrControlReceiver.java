/***************************** BEGIN LICENSE BLOCK ***************************
 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2020-2026 Botts Innovative Research, Inc. All Rights Reserved.
 ******************************* END LICENSE BLOCK ***************************/
package org.sensorhub.impl.sensor.rtlsdr.controls;

import net.opengis.swe.v20.*;
import net.opengis.swe.v20.Boolean;
import org.sensorhub.api.command.CommandException;
import org.sensorhub.impl.sensor.AbstractSensorControl;
import org.sensorhub.impl.sensor.rtlsdr.RtlSdrConfig;
import org.sensorhub.impl.sensor.rtlsdr.RtlSdrConstants;
import org.sensorhub.impl.sensor.rtlsdr.RtlSdrDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vast.swe.SWEConstants;
import org.vast.swe.SWEHelper;

public class RtlSdrControlReceiver extends AbstractSensorControl<RtlSdrDriver> {
    private static final Logger log = LoggerFactory.getLogger(RtlSdrControlReceiver.class);

    private DataRecord commandDataStruct;

    private static final String FIELD_OPERATING_MODE = "operatingMode";
    private static final String FIELD_CENTER_FREQ = "centerFreq";
    private static final String FIELD_GAIN = "gain";
    private static final String FIELD_AUTO_GAIN = "autoGain";
    private static final String FIELD_SAMPLE_RATE = "sampleRate";
    private static final String FIELD_AGC_MODE = "agcMode";
    private static final String FIELD_AUDIO_MODE = "audioMode";

    public RtlSdrControlReceiver(RtlSdrDriver driver) {
        super("receiverControl", driver);
    }

    public void doInit() {
        SWEHelper fac = new SWEHelper();

        RtlSdrConfig cfg = parentSensor != null ? parentSensor.getConfiguration() : null;
        String initOpMode = (cfg != null && cfg.operatingMode != null) ? cfg.operatingMode.name() : "SPECTRUM_AUDIO";
        double initFreq = cfg != null ? cfg.centerFrequency : 98.0;
        double initGain = cfg != null ? cfg.tunerGain : 20.7;
        boolean initAutoGain = cfg != null && cfg.autoGain;
        int initRate = cfg != null ? cfg.sampleRate : 2048000;
        boolean initAgc = cfg != null && cfg.agcMode;
        String initAudioMode = (cfg != null && cfg.audioMode != null) ? cfg.audioMode.name() : "OFF";

        commandDataStruct = fac.createRecord()
                .updatable(true)
                .name("rfReceiverControl")
                .label("RF Receiver Configuration Control")
                .description("Controls for operating mode, tuning center frequency, gain, AGC, and audio demodulation")
                .definition(SWEHelper.getPropertyUri("RfReceiverControl"))
                .addField(FIELD_OPERATING_MODE, fac.createCategory()
                        .label("Operating Mode")
                        .description("Receiver operating mode: SPECTRUM_AUDIO, ADSB_RECEIVER, or AIS_RECEIVER")
                        .definition(SWEHelper.getPropertyUri("OperatingMode"))
                        .addAllowedValues("SPECTRUM_AUDIO", "ADSB_RECEIVER", "AIS_RECEIVER")
                        .value(initOpMode))
                .addField(FIELD_CENTER_FREQ, fac.createQuantity()
                        .uomCode("MHz")
                        .label("Center Frequency")
                        .description("Tuned center frequency in MHz")
                        .definition(SWEConstants.QUDT_URI_PREFIX + "Frequency")
                        .value(initFreq))
                .addField(FIELD_GAIN, fac.createCategory()
                        .label("Receiver Gain (dB)")
                        .description("Input receiver tuner gain in dB")
                        .definition(SWEHelper.getPropertyUri("UniformGain"))
                        .addAllowedValues(RtlSdrConstants.GAIN_VALUES_DB)
                        .value(String.valueOf(initGain)))
                .addField(FIELD_AUTO_GAIN, fac.createBoolean()
                        .label("Auto Gain Control (Tuner)")
                        .description("Enable automatic gain control for tuner")
                        .definition(SWEHelper.getPropertyUri("AutoGain"))
                        .value(initAutoGain))
                .addField(FIELD_SAMPLE_RATE, fac.createQuantity()
                        .uomCode("Hz")
                        .label("Sample Rate")
                        .description("Sample rate in Samples/second (e.g. 2048000)")
                        .definition(SWEConstants.QUDT_URI_PREFIX + "Frequency")
                        .value(initRate))
                .addField(FIELD_AGC_MODE, fac.createBoolean()
                        .label("RTL Digital AGC")
                        .description("Enable digital AGC on RTL2832 chip")
                        .definition(SWEHelper.getPropertyUri("AgcMode"))
                        .value(initAgc))
                .addField(FIELD_AUDIO_MODE, fac.createCategory()
                        .label("Audio Demodulation Mode")
                        .description("Audio demodulation mode: OFF, AM, NFM, WFM, USB, LSB, CW")
                        .definition(SWEHelper.getPropertyUri("AudioDemodMode"))
                        .addAllowedValues("OFF", "AM", "NFM", "WFM", "USB", "LSB", "CW")
                        .value(initAudioMode))
                .build();
    }

    @Override
    protected boolean execCommand(DataBlock cmdData) throws CommandException {
        DataRecord commandData = commandDataStruct.copy();
        commandData.setData(cmdData);

        RtlSdrConfig config = parentSensor.getConfiguration();

        // 1. Center Frequency
        try {
            Quantity freqField = (Quantity) commandData.getField(FIELD_CENTER_FREQ);
            if (freqField != null) {
                double newFreq = freqField.getValue();
                if (newFreq > 0.0 && (config == null || Math.abs(newFreq - config.centerFrequency) > 1e-4)) {
                    log.info("Tuning center frequency via Command Input: {} MHz", newFreq);
                    parentSensor.setCenterFrequency(newFreq);
                }
            }
        } catch (Exception e) {
            log.error("Error setting center frequency: {}", e.getMessage(), e);
            throw new CommandException("Error setting center frequency: " + e.getMessage(), e);
        }

        // 2. Tuner Gain
        try {
            Category gainField = (Category) commandData.getField(FIELD_GAIN);
            if (gainField != null && gainField.getValue() != null && !gainField.getValue().trim().isEmpty()) {
                double newGain = Double.parseDouble(gainField.getValue().trim());
                if (config == null || Math.abs(newGain - config.tunerGain) > 0.05) {
                    log.info("Setting tuner gain via Command Input: {} dB", newGain);
                    parentSensor.setTunerGain(newGain);
                }
            }
        } catch (NumberFormatException ignored) {
        } catch (Exception e) {
            log.warn("Error setting tuner gain: {}", e.getMessage());
        }

        // 3. Auto Gain Control
        try {
            Boolean autoGainField = (Boolean) commandData.getField(FIELD_AUTO_GAIN);
            if (autoGainField != null) {
                boolean newAutoGain = autoGainField.getValue();
                if (config == null || newAutoGain != config.autoGain) {
                    log.info("Setting auto gain via Command Input: {}", newAutoGain);
                    parentSensor.setAutoGain(newAutoGain);
                }
            }
        } catch (Exception e) {
            log.warn("Error setting auto gain: {}", e.getMessage());
        }

        // 4. Sample Rate (only change if explicitly modified from current rate)
        try {
            Quantity sampleRateField = (Quantity) commandData.getField(FIELD_SAMPLE_RATE);
            if (sampleRateField != null) {
                int newRate = (int) Math.round(sampleRateField.getValue());
                if (newRate > 0 && config != null && newRate != config.sampleRate) {
                    log.info("Setting sample rate via Command Input: {} Hz", newRate);
                    parentSensor.setSampleRate(newRate);
                }
            }
        } catch (Exception e) {
            log.warn("Error setting sample rate: {}", e.getMessage());
        }

        // 5. Digital AGC
        try {
            Boolean agcField = (Boolean) commandData.getField(FIELD_AGC_MODE);
            if (agcField != null) {
                boolean newAgc = agcField.getValue();
                if (config == null || newAgc != config.agcMode) {
                    log.info("Setting digital AGC via Command Input: {}", newAgc);
                    parentSensor.setAgcMode(newAgc);
                }
            }
        } catch (Exception e) {
            log.warn("Error setting digital AGC: {}", e.getMessage());
        }

        // 6. Operating Mode
        try {
            Category opModeField = (Category) commandData.getField(FIELD_OPERATING_MODE);
            if (opModeField != null && opModeField.getValue() != null && !opModeField.getValue().trim().isEmpty()) {
                String opModeStr = opModeField.getValue().trim();
                RtlSdrConfig.OperatingMode newOpMode = RtlSdrConfig.OperatingMode.valueOf(opModeStr);
                if (config == null || newOpMode != config.operatingMode) {
                    log.info("Setting operating mode via Command Input: {}", newOpMode);
                    parentSensor.setOperatingMode(newOpMode);
                }
            }
        } catch (Exception e) {
            log.warn("Error setting operating mode: {}", e.getMessage());
        }

        // 7. Audio Demodulation Mode
        try {
            Category audioModeField = (Category) commandData.getField(FIELD_AUDIO_MODE);
            if (audioModeField != null && audioModeField.getValue() != null && !audioModeField.getValue().trim().isEmpty()) {
                String audioModeStr = audioModeField.getValue().trim();
                RtlSdrConfig.AudioDemodMode newAudioMode = RtlSdrConfig.AudioDemodMode.valueOf(audioModeStr);
                if (config == null || newAudioMode != config.audioMode) {
                    log.info("Setting audio demod mode via Command Input: {}", newAudioMode);
                    parentSensor.setAudioMode(newAudioMode);
                }
            }
        } catch (Exception e) {
            log.warn("Error setting audio demod mode: {}", e.getMessage());
        }

        return true;
    }

    @Override
    public DataComponent getCommandDescription() {
        return commandDataStruct;
    }
}
